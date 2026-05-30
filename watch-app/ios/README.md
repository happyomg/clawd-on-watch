# Clawd on Watch — watchOS (Planned)

watchOS companion app for Clawd on Desk. Not yet implemented.

## Architecture

Same BLE Peripheral model as the Android version:
- Watch acts as BLE Peripheral (CBPeripheralManager)
- Mac connects as Central via the existing bleak bridge
- Same GATT service (CWD0) and compact protocol `{s, svg, n}`

## Prerequisites

- Xcode 15+
- watchOS 9+ target
- CoreBluetooth framework

## Status

- [ ] Xcode project setup
- [ ] CBPeripheralManager GATT server
- [ ] BLE advertising with CWD0 service UUID
- [ ] State rendering (SwiftUI or SpriteKit)
- [ ] Approval gesture (Digital Crown / haptic)
