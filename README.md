# LGUltraFineBrightness

Android USB HID brightness controller for LG UltraFine displays exposing `043e:9a63` (`LG UltraFine Display Controls`).

Initial test target: LG 24MD4KLB-B connected to a Samsung Galaxy Note20 Ultra over USB-C / DeX.

The app claims HID interface 1 (`HID BRIGHTNESS`) and uses HID Feature Report 0 to read/write the monitor's hardware backlight level.
