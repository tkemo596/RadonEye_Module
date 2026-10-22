<#
.SYNOPSIS
  Bluetooth LE transport for the RadonEye RD200, using the Windows Bluetooth (WinRT) APIs.
  Used by the Ignition RadonEye driver; also handy on its own for diagnosis.

.DESCRIPTION
  Scan mode lists nearby RadonEye detectors (advertised names start with "FR:"):
      powershell -NoProfile -ExecutionPolicy Bypass -File rd200.ps1 -Scan
  Read mode connects, sends the query commands, prints each raw reply as hex, and disconnects
  (so the Ecosense phone app can still connect between readings):
      powershell -NoProfile -ExecutionPolicy Bypass -File rd200.ps1 -Address AA:BB:CC:DD:EE:FF

  Output is always one line of JSON on stdout. Decoding the replies is done by the driver.

  Requires Windows 10 1803 or later and Windows PowerShell 5.1 (powershell.exe). PowerShell 7
  (pwsh) cannot load the WinRT Bluetooth types.

  Protocol (from github.com/jdeath/rd200v2 and ESPHome's radon_eye_rd200):
    V2: service 00001523-0000-1000-8000-00805f9b34fb, write 0x1524, notify 0x1525
    V1: service 00001523-1212-efde-1523-785feabcd123, write ...1524..., notify ...1525...
    Commands: 0x50 readings, 0x40 (V2) peak + versions, 0x51 uptime (V1: peak + uptime).
#>
param(
  [switch]$Scan,
  [int]$Seconds = 10,
  [string]$Address = "",
  [string]$Commands = "",
  [int]$TimeoutSeconds = 15
)

$ErrorActionPreference = "Stop"

function Out-Json($obj) {
  [Console]::Out.WriteLine(($obj | ConvertTo-Json -Compress -Depth 6))
}

function Fail($message) {
  Out-Json @{ error = [string]$message }
  exit 1
}

if ($PSVersionTable.PSVersion.Major -ge 6) {
  Fail "Run this with Windows PowerShell 5.1 (powershell.exe); PowerShell $($PSVersionTable.PSVersion) cannot load the WinRT Bluetooth APIs."
}

try {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime
  $null = [Windows.Devices.Bluetooth.BluetoothLEDevice, Windows.Devices.Bluetooth, ContentType = WindowsRuntime]
  $null = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattDeviceService, Windows.Devices.Bluetooth, ContentType = WindowsRuntime]
  $null = [Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher, Windows.Devices.Bluetooth, ContentType = WindowsRuntime]
  $null = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattDeviceServicesResult, Windows.Devices.Bluetooth, ContentType = WindowsRuntime]
  $null = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattCharacteristicsResult, Windows.Devices.Bluetooth, ContentType = WindowsRuntime]
  $null = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattCommunicationStatus, Windows.Devices.Bluetooth, ContentType = WindowsRuntime]
  $null = [Windows.Storage.Streams.IBuffer, Windows.Storage.Streams, ContentType = WindowsRuntime]
} catch {
  Fail "Windows Bluetooth APIs are not available: $($_.Exception.Message)"
}

$script:asTaskOp = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]

# Waits for a WinRT IAsyncOperation<T> with a timeout and returns its result.
function Await($op, [Type]$resultType, [int]$ms, [string]$what) {
  $task = $script:asTaskOp.MakeGenericMethod($resultType).Invoke($null, @($op))
  if (-not $task.Wait($ms)) { throw "Timed out after $($ms / 1000) s: $what" }
  return $task.Result
}

function Format-Address([UInt64]$a) {
  $h = $a.ToString("X12")
  return ($h -split '(.{2})' | Where-Object { $_ }) -join ':'
}

function Parse-Address([string]$s) {
  $hex = ($s -replace '[^0-9A-Fa-f]', '')
  if ($hex.Length -ne 12) { Fail "Address must be a Bluetooth MAC such as AA:BB:CC:DD:EE:FF, not '$s'" }
  return [UInt64]::Parse($hex, [Globalization.NumberStyles]::HexNumber)
}

# Releases a WinRT object. PowerShell's own .Dispose() on these throws "The object's type must not
# be a Windows Runtime type", so go through IDisposable explicitly.
function Close-WinRt($o) {
  if ($null -eq $o) { return }
  try { ([System.IDisposable]$o).Dispose() } catch {
    try { [System.IDisposable].GetMethod("Dispose").Invoke($o, $null) } catch { }
  }
}

function To-Hex([byte[]]$b) {
  if ($null -eq $b) { return "" }
  return (($b | ForEach-Object { $_.ToString("x2") }) -join '')
}

$WRT = [System.Runtime.InteropServices.WindowsRuntime.WindowsRuntimeBufferExtensions]

# Windows PowerShell 5.1 refuses Register-ObjectEvent on WinRT events ("cannot subscribe to
# Windows RT events"), and a scriptblock handler would run on a thread with no runspace. So a
# tiny C# class (no WinRT references, compiled by the .NET Framework compiler that ships with
# Windows) subscribes through the event's add method and queues the event args; the script then
# takes them off the queue on its own thread.
if (-not ("Rd200.EventSink" -as [Type])) {
  Add-Type -Language CSharp -TypeDefinition @"
namespace Rd200 {
  using System;
  using System.Collections.Concurrent;
  using System.Reflection;
  public class EventSink {
    private readonly ConcurrentQueue<object> queue = new ConcurrentQueue<object>();
    private object target;
    private MethodInfo remove;
    private object token;
    public void Handle(object sender, object args) { queue.Enqueue(args); }
    public void Subscribe(object source, string eventName) { Subscribe(source, eventName, null); }
    // Objects that come out of WinRT collections can arrive as a bare System.__ComObject, so the
    // caller may name the WinRT type the event is declared on.
    public void Subscribe(object source, string eventName, Type declared) {
      // Try the object's own type, then the declared WinRT class, then the interfaces it
      // implements: a bare __ComObject only matches the interface the event really lives on.
      System.Collections.Generic.List<Type> types = new System.Collections.Generic.List<Type>();
      types.Add(source.GetType());
      if (declared != null) {
        types.Add(declared);
        types.AddRange(declared.GetInterfaces());
      }
      string last = "no event " + eventName + " on " + source.GetType().FullName;
      foreach (Type t in types) {
        EventInfo ev = t.GetEvent(eventName);
        if (ev == null) { continue; }
        try {
          Delegate h = Delegate.CreateDelegate(ev.EventHandlerType, this, typeof(EventSink).GetMethod("Handle"));
          object tok = ev.GetAddMethod().Invoke(source, new object[] { h });
          target = source;
          remove = ev.GetRemoveMethod();
          token = tok;
          return;
        } catch (Exception e) {
          Exception inner = e.InnerException != null ? e.InnerException : e;
          last = t.Name + ": " + inner.Message;
        }
      }
      throw new InvalidOperationException(last);
    }
    public void Unsubscribe() {
      if (remove != null && token != null) {
        try { remove.Invoke(target, new object[] { token }); } catch (Exception) { }
      }
      remove = null;
    }
    public void Clear() { object o; while (queue.TryDequeue(out o)) { } }
    public object Next(int ms) {
      DateTime end = DateTime.UtcNow.AddMilliseconds(ms);
      while (true) {
        object o;
        if (queue.TryDequeue(out o)) { return o; }
        if (DateTime.UtcNow >= end) { return null; }
        System.Threading.Thread.Sleep(20);
      }
    }
  }
}
"@
}

# The read runs inside compiled C#, not PowerShell: GATT objects that PowerShell takes out of a
# WinRT collection lose their type (they become bare System.__ComObject), and then nothing can
# subscribe to their ValueChanged event. Add-Type cannot reference .winmd files, so the .NET
# Framework compiler (csc.exe, part of Windows) builds it once; the DLL is cached in %TEMP%.
$script:gattSource = @"
namespace Rd200 {
  using System;
  using System.Collections.Concurrent;
  using System.Collections.Generic;
  using System.Threading;
  using Windows.Foundation;
  using Windows.Storage.Streams;
  using Windows.Devices.Bluetooth;
  using Windows.Devices.Bluetooth.GenericAttributeProfile;

  public class ReadResult {
    public int Version;
    public string Name;
    public string Error;
    public string Step = "start";
    public Dictionary<string, string> Replies = new Dictionary<string, string>();
    public List<string> Errors = new List<string>();
  }

  public static class Gatt {
    // Waits on a WinRT async operation by polling its status. (AsTask() lives in
    // System.Runtime.WindowsRuntime.dll, which needs the SDK-only union Windows.winmd.)
    static T Await<T>(IAsyncOperation<T> op, int ms, string what) {
      DateTime end = DateTime.UtcNow.AddMilliseconds(ms);
      while (op.Status == AsyncStatus.Started) {
        if (DateTime.UtcNow > end) {
          try { op.Cancel(); } catch (Exception) { }
          throw new TimeoutException("Timed out after " + (ms / 1000) + " s: " + what);
        }
        Thread.Sleep(10);
      }
      if (op.Status == AsyncStatus.Error) { throw new Exception(what + " failed: " + op.ErrorCode.Message); }
      if (op.Status == AsyncStatus.Canceled) { throw new Exception(what + " was cancelled"); }
      return op.GetResults();
    }

    static byte[] ToBytes(IBuffer b) {
      byte[] a = new byte[b.Length];
      DataReader.FromBuffer(b).ReadBytes(a);
      return a;
    }

    static IBuffer FromBytes(byte[] a) {
      DataWriter w = new DataWriter();
      w.WriteBytes(a);
      return w.DetachBuffer();
    }

    static string Hex(byte[] b) {
      var sb = new System.Text.StringBuilder();
      foreach (byte x in b) { sb.Append(x.ToString("x2")); }
      return sb.ToString();
    }

    static string Message(Exception e) {
      while (e is AggregateException && e.InnerException != null) { e = e.InnerException; }
      return e.Message;
    }

    // version, service, write characteristic, notify characteristic, default commands
    static readonly string[][] Variants = {
      new[] { "2", "00001523-0000-1000-8000-00805f9b34fb", "00001524-0000-1000-8000-00805f9b34fb", "00001525-0000-1000-8000-00805f9b34fb", "50,40,51" },
      new[] { "1", "00001523-1212-efde-1523-785feabcd123", "00001524-1212-efde-1523-785feabcd123", "00001525-1212-efde-1523-785feabcd123", "50,51" }
    };

    // PowerShell runs scripts on a single-threaded (STA) COM thread. WinRT completions aimed at it
    // need a message pump, which a polling wait does not run, so do the work on an MTA thread.
    public static ReadResult Read(ulong address, int ms, string commands) {
      ReadResult result = null;
      Exception failure = null;
      Thread t = new Thread(() => {
        try { result = ReadCore(address, ms, commands); } catch (Exception e) { failure = e; }
      });
      t.SetApartmentState(ApartmentState.MTA);
      t.IsBackground = true;
      t.Start();
      t.Join();
      if (failure != null) {
        result = new ReadResult();
        result.Error = Message(failure);
      }
      return result;
    }

    static ReadResult ReadCore(ulong address, int ms, string commands) {
      var r = new ReadResult();
      BluetoothLEDevice device = null;
      GattDeviceService service = null;
      GattCharacteristic notify = null;
      TypedEventHandler<GattCharacteristic, GattValueChangedEventArgs> handler = null;
      var queue = new BlockingCollection<byte[]>();
      try {
        r.Step = "connect";
        device = Await(BluetoothLEDevice.FromBluetoothAddressAsync(address), ms, "connect");
        if (device == null) { throw new Exception("No Bluetooth LE device at that address (out of range, or Bluetooth is off)"); }
        r.Name = device.Name;

        r.Step = "service discovery";
        string[] v = null;
        foreach (var cand in Variants) {
          var sr = Await(device.GetGattServicesForUuidAsync(new Guid(cand[1]), BluetoothCacheMode.Uncached), ms, "service discovery");
          if (sr.Status != GattCommunicationStatus.Success) {
            throw new Exception("Service discovery failed: " + sr.Status + " (the Ecosense app may be connected - close it)");
          }
          if (sr.Services.Count > 0) { service = sr.Services[0]; v = cand; break; }
        }
        if (v == null) { throw new Exception(device.Name + " has no RadonEye service"); }
        r.Version = int.Parse(v[0]);

        r.Step = "characteristics";
        var wr = Await(service.GetCharacteristicsForUuidAsync(new Guid(v[2]), BluetoothCacheMode.Uncached), ms, "characteristics");
        var nr = Await(service.GetCharacteristicsForUuidAsync(new Guid(v[3]), BluetoothCacheMode.Uncached), ms, "characteristics");
        if (wr.Characteristics.Count == 0 || nr.Characteristics.Count == 0) { throw new Exception("RadonEye characteristics not found"); }
        GattCharacteristic write = wr.Characteristics[0];
        notify = nr.Characteristics[0];

        r.Step = "subscribe to notifications";
        handler = (s, a) => queue.Add(ToBytes(a.CharacteristicValue));
        notify.ValueChanged += handler;

        r.Step = "enable notifications";
        var st = Await(notify.WriteClientCharacteristicConfigurationDescriptorAsync(
            GattClientCharacteristicConfigurationDescriptorValue.Notify), ms, "enable notifications");
        if (st != GattCommunicationStatus.Success) { throw new Exception("Could not enable notifications: " + st); }

        var option = (write.CharacteristicProperties & GattCharacteristicProperties.Write) != 0
            ? GattWriteOption.WriteWithResponse : GattWriteOption.WriteWithoutResponse;

        foreach (string c in (string.IsNullOrEmpty(commands) ? v[4] : commands).Split(',')) {
          string cmd = c.Trim().ToLowerInvariant();
          r.Step = "command " + cmd;
          byte[] ignored;
          while (queue.TryTake(out ignored)) { }
          var ws = Await(write.WriteValueAsync(FromBytes(new byte[] { Convert.ToByte(cmd, 16) }), option), ms, "write " + cmd);
          if (ws != GattCommunicationStatus.Success) { r.Errors.Add("write " + cmd + ": " + ws); continue; }
          // A long reply may arrive as several notifications: read until 400 ms of quiet.
          var reply = new List<byte>();
          byte[] part;
          if (queue.TryTake(out part, ms)) {
            reply.AddRange(part);
            while (queue.TryTake(out part, 400)) { reply.AddRange(part); }
          }
          if (reply.Count == 0) { r.Errors.Add("no reply to " + cmd); }
          else { r.Replies[cmd] = Hex(reply.ToArray()); }
        }
        r.Step = "done";
      } catch (Exception e) {
        r.Errors.Add(Message(e));
        if (r.Replies.Count == 0) { r.Error = Message(e); }
      } finally {
        if (notify != null) {
          if (handler != null) { try { notify.ValueChanged -= handler; } catch (Exception) { } }
          try {
            Await(notify.WriteClientCharacteristicConfigurationDescriptorAsync(
                GattClientCharacteristicConfigurationDescriptorValue.None), 3000, "disable notifications");
          } catch (Exception) { }
        }
        // Windows keeps the link up while anything references it; release it so the RD200 is
        // free for the Ecosense app and for the next reading.
        try { if (service != null) { service.Dispose(); } } catch (Exception) { }
        try { if (device != null) { device.Dispose(); } } catch (Exception) { }
      }
      return r;
    }
  }
}
"@

function Get-GattType {
  if ("Rd200.Gatt" -as [Type]) { return }
  $fw = [System.Runtime.InteropServices.RuntimeEnvironment]::GetRuntimeDirectory()
  $csc = Join-Path $fw "csc.exe"
  if (-not (Test-Path $csc)) { Fail "csc.exe not found in $fw" }
  $sha = [System.Security.Cryptography.SHA1]::Create()
  $hash = (($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($script:gattSource)) | ForEach-Object { $_.ToString("x2") }) -join '').Substring(0, 12)
  $dir = Join-Path ([IO.Path]::GetTempPath()) "rd200-$hash"
  $dll = Join-Path $dir "Rd200Gatt.dll"
  if (-not (Test-Path $dll)) {
    $null = New-Item -ItemType Directory -Force -Path $dir
    $src = Join-Path $dir "Gatt.cs"
    [IO.File]::WriteAllText($src, $script:gattSource)
    $meta = Join-Path $env:SystemRoot "System32\WinMetadata"
    $refs = @(
      (Join-Path $meta "Windows.Foundation.winmd"),
      (Join-Path $meta "Windows.Devices.winmd"),
      (Join-Path $meta "Windows.Storage.winmd"),
      (Join-Path $fw "System.Runtime.dll"),
      (Join-Path $fw "System.Runtime.InteropServices.WindowsRuntime.dll"),
      (Join-Path $fw "System.Threading.Tasks.dll")
    ) | Where-Object { Test-Path $_ }
    $cscArgs = @("/nologo", "/target:library", "/out:$dll") + ($refs | ForEach-Object { "/r:$_" }) + @($src)
    $out = & $csc $cscArgs 2>&1 | Out-String
    if (-not (Test-Path $dll)) { Fail "Could not compile the Bluetooth helper: $($out.Trim())" }
  }
  $null = [Reflection.Assembly]::LoadFrom($dll)
}


# ------------------------------------------------------------------- scan
if ($Scan) {
  $watcher = New-Object Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher
  $watcher.ScanningMode = [Windows.Devices.Bluetooth.Advertisement.BluetoothLEScanningMode]::Active
  $found = @{}
  $sink = New-Object Rd200.EventSink
  $sink.Subscribe($watcher, "Received")
  try {
    $watcher.Start()
    Start-Sleep -Milliseconds 300
    if ($watcher.Status -eq [Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcherStatus]::Aborted) {
      Fail "Bluetooth scan aborted - is Bluetooth turned on?"
    }
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
      $a = $sink.Next(500)
      if ($null -eq $a) { continue }
      $name = $a.Advertisement.LocalName
      $addr = Format-Address $a.BluetoothAddress
      if ($found.ContainsKey($addr)) {
        if ($name -and -not $found[$addr].name) { $found[$addr].name = $name }
        $found[$addr].rssi = [int]$a.RawSignalStrengthInDBm
      } elseif ($name -like "FR:*") {
        $found[$addr] = @{ address = $addr; name = $name; rssi = [int]$a.RawSignalStrengthInDBm }
      }
    }
  } finally {
    try { $watcher.Stop() } catch { }
    $sink.Unsubscribe()
  }
  Out-Json @{ mode = "scan"; seconds = $Seconds; devices = @($found.Values) }
  exit 0
}

# ------------------------------------------------------------------- read
if (-not $Address) { Fail "Give -Scan, or -Address AA:BB:CC:DD:EE:FF" }

$addr64 = Parse-Address $Address
Get-GattType
$r = [Rd200.Gatt]::Read($addr64, $TimeoutSeconds * 1000, $Commands)
if ($r.Error) { Fail "$($r.Error) (while: $($r.Step))" }

$replies = @{}
foreach ($k in $r.Replies.Keys) { $replies[$k] = $r.Replies[$k] }
Out-Json @{ mode = "read"; address = (Format-Address $addr64); name = $r.Name; version = $r.Version;
  replies = $replies; errors = @($r.Errors) }
exit 0
