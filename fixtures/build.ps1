# CrossCheck Mode 2 fixture builder (Windows PowerShell 5.1)
# Generates 10 HTML mock screens (no branded assets), screenshots them with headless Chrome
# at 1080x2400, and verifies each PNG (dimensions, non-blank, glyph colour, amount ink).
#
# Usage:  powershell -ExecutionPolicy Bypass -File .\build.ps1 [-SkipRender] [-SkipVerify]

param([switch]$SkipRender, [switch]$SkipVerify)

$ErrorActionPreference = 'Stop'
$here   = Split-Path -Parent $MyInvocation.MyCommand.Path
$chrome = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
$udd    = Join-Path $env:TEMP 'crosscheck-fixtures-chrome-profile'

# ---------------------------------------------------------------- templates ----
$head = @'
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>{{TITLE}}</title>
<style>
html,body{margin:0;padding:0;width:1080px;height:2400px;overflow:hidden;font-family:Roboto,"Segoe UI",Arial,Helvetica,sans-serif;-webkit-font-smoothing:antialiased;}
.screen{position:relative;width:1080px;height:2400px;overflow:hidden;}
.statusbar{height:90px;display:flex;justify-content:space-between;align-items:center;padding:0 48px;font-size:34px;}
.sig{display:inline-block;width:0;height:0;border-left:16px solid transparent;border-right:16px solid transparent;border-bottom:26px solid currentColor;margin-right:14px;}
.bat{display:inline-block;width:52px;height:24px;border:4px solid currentColor;border-radius:6px;position:relative;vertical-align:middle;}
.bat::after{content:"";position:absolute;left:4px;top:4px;bottom:4px;width:70%;background:currentColor;}
{{STYLE}}
</style></head><body>
'@

$gpayStyle = @'
body{background:#ffffff;color:#202124;}
.screen{background:#ffffff;}
.topbar{height:120px;display:flex;justify-content:space-between;align-items:center;padding:0 44px;font-size:60px;color:#5f6368;}
.hero{text-align:center;padding-top:70px;}
.glyph{width:220px;height:220px;border-radius:50%;margin:0 auto;position:relative;background:#1E8E3E;}
.glyph.pending{background:#9AA0A6;}
.tick{position:absolute;left:68px;top:44px;width:46px;height:100px;border-right:22px solid #fff;border-bottom:22px solid #fff;transform:rotate(45deg);}
.clockface{position:absolute;left:36px;top:36px;width:148px;height:148px;border-radius:50%;border:16px solid #fff;box-sizing:border-box;}
.hand-h{position:absolute;left:102px;top:62px;width:16px;height:56px;background:#fff;border-radius:8px;}
.hand-m{position:absolute;left:102px;top:102px;width:64px;height:16px;background:#fff;border-radius:8px;}
.amount{font-size:136px;font-weight:700;margin-top:64px;line-height:1.1;letter-spacing:-2px;color:#202124;}
.payee{font-size:50px;margin-top:26px;color:#202124;}
.status{font-size:40px;margin-top:44px;color:#5f6368;}
.time{font-size:40px;margin-top:12px;color:#5f6368;}
.divider{height:2px;background:#dadce0;margin:84px 48px 0;}
.details{padding:64px 64px 0;}
.row{margin-bottom:54px;}
.label{font-size:34px;color:#5f6368;margin-bottom:10px;}
.value{font-size:44px;color:#202124;}
.line{font-size:40px;color:#202124;margin-bottom:54px;}
.buttons{position:absolute;bottom:130px;left:0;right:0;display:flex;justify-content:center;gap:40px;}
.btn{font-size:40px;padding:30px 60px;border-radius:60px;border:3px solid #dadce0;color:#1a73e8;font-weight:500;background:#fff;}
.btn.primary{background:#1a73e8;color:#fff;border-color:#1a73e8;}
'@

$gpayBody = @'
<div class="screen">
  <div class="statusbar"><span>12:41</span><span><span class="sig"></span><span class="bat"></span></span></div>
  <div class="topbar"><span>&#8592;</span><span>&#8942;</span></div>
  <div class="hero">
    {{GLYPH}}
    <div class="amount">{{AMOUNT}}</div>
    <div class="payee">{{PAYEE_LINE}}</div>
    <div class="status">{{STATUS}}</div>
    <div class="time">6 Sept 2026, 12:41 pm</div>
  </div>
  <div class="divider"></div>
  <div class="details">
    {{UTR_ROW}}
    <div class="line">To: SHERWIN STORES (sherwin@oksbi)</div>
    <div class="line">From: MURUGAN (State Bank of India XX4321)</div>
    <div class="row"><div class="label">Google transaction ID</div><div class="value">{{GTXN}}</div></div>
  </div>
  <div class="buttons"><div class="btn">Share screenshot</div><div class="btn primary">Close</div></div>
</div>
'@

$gpayTick  = '<div class="glyph"><div class="tick"></div></div>'
$gpayClock = '<div class="glyph pending"><div class="clockface"></div><div class="hand-h"></div><div class="hand-m"></div></div>'
$gpayUtrRow = '<div class="row"><div class="label">UPI transaction ID</div><div class="value">{{UTR}}</div></div>'

$ppStyle = @'
body{background:#F3F3F8;color:#1c1c28;}
.screen{background:#F3F3F8;}
.topbar{height:110px;display:flex;justify-content:space-between;align-items:center;padding:0 44px;font-size:60px;color:#3d3d4e;}
.topbar .help{font-size:36px;color:#5F259F;font-weight:600;}
.hero{text-align:center;padding-top:36px;}
.glyph{width:200px;height:200px;border-radius:50%;margin:0 auto;position:relative;background:#00B386;}
.glyph.pending{background:#F5A623;}
.tick{position:absolute;left:62px;top:40px;width:42px;height:92px;border-right:20px solid #fff;border-bottom:20px solid #fff;transform:rotate(45deg);}
.clockface{position:absolute;left:32px;top:32px;width:136px;height:136px;border-radius:50%;border:16px solid #fff;box-sizing:border-box;}
.hand-h{position:absolute;left:92px;top:56px;width:16px;height:52px;background:#fff;border-radius:8px;}
.hand-m{position:absolute;left:92px;top:92px;width:58px;height:16px;background:#fff;border-radius:8px;}
.heading{font-size:62px;font-weight:700;margin-top:36px;color:{{HEADING_COLOR}};}
.time{font-size:38px;color:#6b6b80;margin-top:16px;}
.card{background:#fff;border-radius:32px;margin:56px 40px 0;padding:48px 52px;box-shadow:0 2px 8px rgba(0,0,0,.06);}
.paidto{display:flex;justify-content:space-between;align-items:center;}
.paidto .label{font-size:34px;color:#6b6b80;}
.paidto .name{font-size:46px;font-weight:600;margin-top:12px;color:#1c1c28;}
.paidto .vpa{font-size:34px;color:#6b6b80;margin-top:10px;}
.paidto .amount{font-size:124px;font-weight:700;text-align:right;line-height:1.1;letter-spacing:-2px;color:#1c1c28;}
.section{font-size:40px;font-weight:600;margin:64px 64px 0;color:#1c1c28;}
.card.details{margin-top:28px;}
.row{margin-bottom:48px;}
.row:last-child{margin-bottom:0;}
.label{font-size:34px;color:#6b6b80;margin-bottom:10px;}
.value{font-size:42px;color:#1c1c28;}
.copy{display:inline-block;width:26px;height:32px;border:4px solid #5F259F;border-radius:4px;margin-left:20px;vertical-align:middle;position:relative;}
.copy::after{content:"";position:absolute;left:8px;top:8px;width:26px;height:32px;border:4px solid #5F259F;border-radius:4px;background:#fff;}
.debit{display:flex;align-items:center;justify-content:space-between;margin-top:14px;}
.debit .left{display:flex;align-items:center;gap:28px;}
.bank{width:96px;height:96px;border-radius:20px;background:#ECEAF3;position:relative;}
.bank::before{content:"";position:absolute;left:14px;top:22px;width:68px;height:10px;background:#5F259F;border-radius:3px;}
.bank::after{content:"";position:absolute;left:14px;top:66px;width:68px;height:10px;background:#5F259F;border-radius:3px;}
.bank .col{position:absolute;top:34px;width:10px;height:30px;background:#5F259F;}
.bank .c1{left:20px;}.bank .c2{left:43px;}.bank .c3{left:66px;}
.debit .bankname{font-size:40px;color:#1c1c28;}
.debit .mask{font-size:34px;color:#6b6b80;margin-top:8px;}
.debit .amt{font-size:42px;font-weight:700;color:#1c1c28;}
.buttons{position:absolute;bottom:120px;left:40px;right:40px;display:flex;gap:32px;}
.btn{flex:1;text-align:center;font-size:42px;font-weight:600;padding:34px 0;border-radius:24px;border:4px solid #5F259F;color:#5F259F;background:#fff;}
.btn.primary{background:#5F259F;color:#fff;}
'@

$ppBody = @'
<div class="screen">
  <div class="statusbar"><span>12:41</span><span><span class="sig"></span><span class="bat"></span></span></div>
  <div class="topbar"><span>&#8592;</span><span class="help">Help</span></div>
  <div class="hero">
    {{GLYPH}}
    <div class="heading">{{HEADING}}</div>
    <div class="time">06 Sep 2026, 12:41 pm</div>
  </div>
  <div class="card">
    <div class="paidto">
      <div><div class="label">Paid to</div><div class="name">SHERWIN STORES</div><div class="vpa">sherwin@ybl</div></div>
      <div class="amount">{{AMOUNT}}</div>
    </div>
  </div>
  <div class="section">Transfer Details</div>
  <div class="card details">
    <div class="row"><div class="label">Transaction ID</div><div class="value">{{TID}}<span class="copy"></span></div></div>
    {{UTR_ROW}}
    <div class="row"><div class="label">Debited from</div>
      <div class="debit">
        <div class="left"><div class="bank"><div class="col c1"></div><div class="col c2"></div><div class="col c3"></div></div>
          <div><div class="bankname">State Bank of India</div><div class="mask">XXXXXX4321</div></div></div>
        <div class="amt">{{AMOUNT}}</div>
      </div>
    </div>
  </div>
  <div class="buttons"><div class="btn">Share</div><div class="btn primary">Done</div></div>
</div>
'@

$ppTick  = '<div class="glyph"><div class="tick"></div></div>'
$ppClock = '<div class="glyph pending"><div class="clockface"></div><div class="hand-h"></div><div class="hand-m"></div></div>'
$ppUtrRow = '<div class="row"><div class="label">UTR</div><div class="value">{{UTR}}<span class="copy"></span></div></div>'

# Photo-like wrapper: dark surround, slight perspective tilt, soft diagonal glare (~15% white).
$photoStyle = @'
body{background:#0e0e0e;}
.scene{position:relative;width:1080px;height:2400px;overflow:hidden;background:radial-gradient(ellipse at 45% 35%,#2b2b2e 0%,#141416 55%,#070708 100%);}
.phone{position:absolute;left:0;top:0;width:1080px;height:2400px;transform-origin:50% 50%;transform:perspective(2800px) rotateY(6deg) scale(0.86);border-radius:64px;overflow:hidden;box-shadow:0 0 0 16px #1d1d20,0 0 0 20px #0a0a0a,0 60px 140px rgba(0,0,0,.85);}
.glare{position:absolute;left:0;top:0;width:1080px;height:2400px;pointer-events:none;background:linear-gradient(118deg,rgba(255,255,255,0) 28%,rgba(255,255,255,.15) 44%,rgba(255,255,255,.15) 56%,rgba(255,255,255,0) 72%);}
'@

$tail = '</body></html>'

function Build-Html([string]$style, [string]$body, [hashtable]$vars, [bool]$photo) {
  $h = $head.Replace('{{TITLE}}', $vars['TITLE'])
  $s = $style
  if ($photo) { $s = $s + "`n" + $photoStyle }
  $h = $h.Replace('{{STYLE}}', $s)
  $b = $body
  foreach ($k in $vars.Keys) { $b = $b.Replace('{{' + $k + '}}', [string]$vars[$k]) }
  # second pass for placeholders introduced by row fragments (e.g. {{UTR}} inside {{UTR_ROW}})
  foreach ($k in $vars.Keys) { $b = $b.Replace('{{' + $k + '}}', [string]$vars[$k]) }
  if ($photo) { $b = '<div class="scene"><div class="phone">' + "`n" + $b + '<div class="glare"></div></div></div>' }
  return $h + $b + $tail
}

# ---------------------------------------------------------------- fixtures ----
$fixtures = @(
  @{ file='gpay_genuine';       app='gpay'; photo=$false; vars=@{ TITLE='gpay_genuine'; GLYPH=$gpayTick;  AMOUNT='&#8377;500';   PAYEE_LINE='Paid to SHERWIN STORES'; STATUS='Completed';   UTR_ROW=$gpayUtrRow; UTR='426112345678'; GTXN='CICAgKDx1b3fSA' } },
  @{ file='gpay_wrong_utr';     app='gpay'; photo=$false; vars=@{ TITLE='gpay_wrong_utr'; GLYPH=$gpayTick;  AMOUNT='&#8377;500';   PAYEE_LINE='Paid to SHERWIN STORES'; STATUS='Completed';   UTR_ROW=$gpayUtrRow; UTR='519876543210'; GTXN='CICAgMzQ9pXbQg' } },
  @{ file='gpay_wrong_amount';  app='gpay'; photo=$false; vars=@{ TITLE='gpay_wrong_amount'; GLYPH=$gpayTick;  AMOUNT='&#8377;5,000'; PAYEE_LINE='Paid to SHERWIN STORES'; STATUS='Completed';   UTR_ROW=$gpayUtrRow; UTR='426112345678'; GTXN='CICAgKDx1b3fSA' } },
  @{ file='gpay_pending';       app='gpay'; photo=$false; vars=@{ TITLE='gpay_pending'; GLYPH=$gpayClock; AMOUNT='&#8377;500';   PAYEE_LINE='Paying SHERWIN STORES';  STATUS='In progress'; UTR_ROW='';          UTR='';             GTXN='CICAgLm2c7vXRw' } },
  @{ file='phonepe_genuine';      app='phonepe'; photo=$false; vars=@{ TITLE='phonepe_genuine'; GLYPH=$ppTick;  HEADING='Payment Successful'; HEADING_COLOR='#00B386'; AMOUNT='&#8377;500';   TID='T2609061241123456789012'; UTR_ROW=$ppUtrRow; UTR='426112345678' } },
  @{ file='phonepe_wrong_utr';    app='phonepe'; photo=$false; vars=@{ TITLE='phonepe_wrong_utr'; GLYPH=$ppTick;  HEADING='Payment Successful'; HEADING_COLOR='#00B386'; AMOUNT='&#8377;500';   TID='T2609061240987654321098'; UTR_ROW=$ppUtrRow; UTR='519876543210' } },
  @{ file='phonepe_wrong_amount'; app='phonepe'; photo=$false; vars=@{ TITLE='phonepe_wrong_amount'; GLYPH=$ppTick;  HEADING='Payment Successful'; HEADING_COLOR='#00B386'; AMOUNT='&#8377;5,000'; TID='T2609061241123456789012'; UTR_ROW=$ppUtrRow; UTR='426112345678' } },
  @{ file='phonepe_pending';      app='phonepe'; photo=$false; vars=@{ TITLE='phonepe_pending'; GLYPH=$ppClock; HEADING='Payment Pending';    HEADING_COLOR='#D98A00'; AMOUNT='&#8377;500';   TID='T2609061241555555555555'; UTR_ROW='';        UTR='' } },
  @{ file='gpay_genuine_photo';    app='gpay';    photo=$true; vars=@{ TITLE='gpay_genuine_photo'; GLYPH=$gpayTick; AMOUNT='&#8377;500'; PAYEE_LINE='Paid to SHERWIN STORES'; STATUS='Completed'; UTR_ROW=$gpayUtrRow; UTR='426112345678'; GTXN='CICAgKDx1b3fSA' } },
  @{ file='phonepe_genuine_photo'; app='phonepe'; photo=$true; vars=@{ TITLE='phonepe_genuine_photo'; GLYPH=$ppTick; HEADING='Payment Successful'; HEADING_COLOR='#00B386'; AMOUNT='&#8377;500'; TID='T2609061241123456789012'; UTR_ROW=$ppUtrRow; UTR='426112345678' } }
)

# ---------------------------------------------------------------- write html ----
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
foreach ($f in $fixtures) {
  if ($f.app -eq 'gpay') { $style = $gpayStyle; $body = $gpayBody } else { $style = $ppStyle; $body = $ppBody }
  $style = $style.Replace('{{HEADING_COLOR}}', [string]$f.vars['HEADING_COLOR'])
  $html = Build-Html $style $body $f.vars $f.photo
  $path = Join-Path $here ($f.file + '.html')
  [System.IO.File]::WriteAllText($path, $html, $utf8NoBom)
  Write-Host ("wrote " + $path)
}

# ---------------------------------------------------------------- render ----
if (-not $SkipRender) {
  if (-not (Test-Path $chrome)) { throw "Chrome not found at $chrome" }
  New-Item -ItemType Directory -Force $udd | Out-Null
  foreach ($f in $fixtures) {
    $html = Join-Path $here ($f.file + '.html')
    $png  = Join-Path $here ($f.file + '.png')
    if (Test-Path $png) { Remove-Item $png -Force }
    $url  = 'file:///' + ($html -replace '\\','/').Replace(' ', '%20')
    # PS 5.1 Start-Process does not quote array elements containing spaces, so build one pre-quoted string.
    $common = "--disable-gpu --hide-scrollbars --force-device-scale-factor=1 --no-first-run --no-default-browser-check `"--user-data-dir=$udd`" --window-size=1080,2400 `"--screenshot=$png`" `"$url`""
    $p = Start-Process -FilePath $chrome -ArgumentList ("--headless=new " + $common) -Wait -PassThru -WindowStyle Hidden
    if (-not (Test-Path $png)) {
      Write-Host "  --headless=new produced nothing (exit $($p.ExitCode)); retrying with --headless"
      $p = Start-Process -FilePath $chrome -ArgumentList ("--headless " + $common) -Wait -PassThru -WindowStyle Hidden
    }
    if (-not (Test-Path $png)) { throw "screenshot failed for $($f.file) (exit $($p.ExitCode))" }
    Write-Host ("rendered " + $png + " (" + (Get-Item $png).Length + " bytes)")
  }
}

# ---------------------------------------------------------------- verify ----
if (-not $SkipVerify) {
  Add-Type -AssemblyName System.Drawing
  $fail = 0
  foreach ($f in $fixtures) {
    $png = Join-Path $here ($f.file + '.png')
    $bmp = New-Object System.Drawing.Bitmap $png
    try {
      $ok = ($bmp.Width -eq 1080 -and $bmp.Height -eq 2400)
      # non-white fraction over a coarse grid
      $n = 0; $ink = 0
      for ($y = 0; $y -lt $bmp.Height; $y += 8) { for ($x = 0; $x -lt $bmp.Width; $x += 8) {
        $c = $bmp.GetPixel($x, $y); $n++
        if (($c.R + $c.G + $c.B) -lt 700) { $ink++ }
      } }
      $inkFrac = [math]::Round($ink / $n, 3)
      # glyph centre (screen coords); for photo variants map through scale(0.86) about (540,1200) - x on the centre column is unchanged by rotateY
      if ($f.app -eq 'gpay') { $gx = 540; $gy = 90 + 120 + 70 + 110 } else { $gx = 540; $gy = 90 + 110 + 36 + 100 }
      # sample lower-left of the disc (radius ~90 px): inside the circle, outside the tick and the clock ring/hands
      $gx -= 64; $gy += 64
      if ($f.photo) { $gx = [int](540 + ($gx - 540) * 0.86); $gy = [int](1200 + ($gy - 1200) * 0.86) }
      $g = $bmp.GetPixel($gx, $gy)
      $glyphClass = 'other'
      if ($g.G -gt 120 -and $g.G -gt $g.R + 40 -and $g.G -gt $g.B) { $glyphClass = 'green' }
      elseif ($g.R -gt 200 -and $g.G -gt 130 -and $g.B -lt 90) { $glyphClass = 'amber' }
      elseif ([math]::Abs($g.R - $g.G) -lt 12 -and [math]::Abs($g.G - $g.B) -lt 12 -and $g.R -gt 120 -and $g.R -lt 190) { $glyphClass = 'grey' }
      # amount row: count dark pixels on a band where the amount glyphs sit
      if ($f.app -eq 'gpay') { $ay0 = 90+120+70+220+64+20; $ay1 = $ay0 + 90 } else { $ay0 = 90+110+36+200+36+80+16+60+56+48+10; $ay1 = $ay0 + 110 }
      if ($f.photo) { $ay0 = [int](1200 + ($ay0 - 1200) * 0.86); $ay1 = [int](1200 + ($ay1 - 1200) * 0.86) }
      $dark = 0
      for ($y = $ay0; $y -lt $ay1; $y += 2) { for ($x = 0; $x -lt $bmp.Width; $x += 2) { $c = $bmp.GetPixel($x, $y); if (($c.R + $c.G + $c.B) -lt 200) { $dark++ } } }
      $expectGlyph = 'green'; if ($f.file -eq 'gpay_pending') { $expectGlyph = 'grey' }; if ($f.file -eq 'phonepe_pending') { $expectGlyph = 'amber' }
      $pass = $ok -and $inkFrac -gt 0.02 -and $glyphClass -eq $expectGlyph -and $dark -gt 300
      if (-not $pass) { $fail++ }
      $tag = 'PASS'; if (-not $pass) { $tag = 'FAIL' }
      Write-Host ("[{0}] {1,-24} {2}x{3}  ink={4}  glyph@({5},{6})=rgb({7},{8},{9})->{10} (expect {11})  amountDarkPx(y{12}-{13})={14}" -f $tag, $f.file, $bmp.Width, $bmp.Height, $inkFrac, $gx, $gy, $g.R, $g.G, $g.B, $glyphClass, $expectGlyph, $ay0, $ay1, $dark)
    } finally { $bmp.Dispose() }
  }
  if ($fail -gt 0) { Write-Host "$fail fixture(s) failed verification"; exit 1 } else { Write-Host 'all fixtures verified' }
}
