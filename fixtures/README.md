# Mode 2 test fixtures

Mock UPI "payment successful" / "pending" screens for CrossCheck's Mode 2 (dispute verification) tests, per `docs/research/payment-screens.md` section 4 and `docs/SPEC.md` sections 1 and 4.

**No branded assets.** No Google Pay / PhonePe logos, wordmarks, or copied screenshots. Glyphs are plain CSS shapes (circle-with-tick, clock, bank-columns), fonts are system fonts (`Roboto, "Segoe UI", Arial`), and only the described colours, layout, and label order are reproduced so that ML Kit OCR yields the same text lines in the same regions as a real screen.

## Files

| File | App | Case | Amount | UTR | Other ID | Status | Verdict |
|---|---|---|---|---|---|---|---|
| `gpay_genuine.png` | GPay | genuine | ₹500 | 426112345678 | CICAgKDx1b3fSA | Completed | Match |
| `gpay_wrong_utr.png` | GPay | fake, wrong UTR | ₹500 | 519876543210 | CICAgMzQ9pXbQg | Completed | NoMatch(WRONG_UTR) |
| `gpay_wrong_amount.png` | GPay | fake, wrong amount | ₹5,000 | 426112345678 | CICAgKDx1b3fSA | Completed | NoMatch(WRONG_AMOUNT) |
| `gpay_pending.png` | GPay | pending | ₹500 | (row absent) | CICAgLm2c7vXRw | In progress | NoMatch(PENDING) |
| `phonepe_genuine.png` | PhonePe | genuine | ₹500 | 426112345678 | T2609061241123456789012 | Payment Successful | Match |
| `phonepe_wrong_utr.png` | PhonePe | fake, wrong UTR | ₹500 | 519876543210 | T2609061240987654321098 | Payment Successful | NoMatch(WRONG_UTR) |
| `phonepe_wrong_amount.png` | PhonePe | fake, wrong amount | ₹5,000 | 426112345678 | T2609061241123456789012 | Payment Successful | NoMatch(WRONG_AMOUNT) |
| `phonepe_pending.png` | PhonePe | pending | ₹500 | (row absent) | T2609061241555555555555 | Payment Pending | NoMatch(PENDING) |
| `gpay_genuine_photo.png` | GPay | genuine, photo-like | same as `gpay_genuine` | | | | Match |
| `phonepe_genuine_photo.png` | PhonePe | genuine, photo-like | same as `phonepe_genuine` | | | | Match |

Every `.png` has a matching `.html` source (inline CSS, no external resources). `fixtures.json` is the machine-readable manifest with the expected extraction per image and the expected reconciler verdict.

All images are 1080x2400 portrait, light theme. Headline amount is 124-136 px font (glyphs > 90 px tall), body text >= 40 px, labels >= 34 px.

The `*_photo` variants draw the same screen inside a dark surround with `perspective(2800px) rotateY(6deg) scale(0.86)`, a rounded bezel, and a diagonal white glare gradient peaking at 15% opacity, to simulate a phone photographing another phone.

## Seeded payments these fixtures are reconciled against

| id | amountPaise | sender | utr | timestamp (IST) | source |
|---|---|---|---|---|---|
| p1 | 50000 | MURUGAN | 426112345678 | 2026-09-06 12:41 | sms |
| p2 | 125000 | PRIYA | 624911223344 | 2026-09-06 11:05 | notification |

Seller identity on all mocks: payee `SHERWIN STORES`, VPA `sherwin@oksbi` (GPay) / `sherwin@ybl` (PhonePe). Payer line: `From: MURUGAN (State Bank of India XX4321)` (GPay) / `Debited from State Bank of India XXXXXX4321` (PhonePe). Note the payer's own VPA is not on screen on either app, which is why `payerName` is null for PhonePe in the manifest.

## Regenerating

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1            # write HTML, screenshot, verify
powershell -ExecutionPolicy Bypass -File .\build.ps1 -SkipRender # verify existing PNGs only
```

`build.ps1` writes the HTML from two templates (GPay / PhonePe), screenshots each with headless Chrome (`chrome.exe --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 --window-size=1080,2400 --screenshot=...`), then loads each PNG with `System.Drawing.Bitmap` and checks: dimensions are 1080x2400, the page is non-blank (ink fraction over an 8 px grid), the status glyph disc is green (success) / grey (GPay pending) / amber (PhonePe pending), and the headline amount band contains dark text pixels.

Text rendering depends on the fonts installed on the rendering machine (Segoe UI on this Windows box, since Roboto is not installed); this changes glyph shapes slightly but not the text content or layout.
