# DevUSB Tool

แอปสำหรับช่างและนักพัฒนา ใช้ดูและแก้ Developer settings และตรวจหรือสลับ USB host/device mode บน head unit ชิป RK3399 ที่รัน Android 10

APK พร้อมติดตั้ง: `dist/DevUSBTool-1.0-debug.apk`

## ติดตั้ง

1. Copy APK ลง USB drive แล้วเปิดด้วย file manager บน head unit (ต้องเปิด "Install unknown apps" ก่อน)
2. หรือใช้ `adb install -r dist/DevUSBTool-1.0-debug.apk`

## เปิดสิทธิ์แก้ค่า (ทำครั้งเดียว)

ถ้ายังไม่ได้ grant แอปจะเป็นโหมดอ่านอย่างเดียว ต้องต่อ adb หนึ่งครั้งเพื่อ grant สิทธิ์

**ผ่านเครือข่าย** (ใช้ได้แม้ USB ยังเป็น host mode):

1. บน head unit: Developer options → เปิด "ADB over network" (ROM ของ Rockchip ส่วนใหญ่มี) แล้วจด IP
2. บนคอม:
   ```bash
   adb connect <IP>:5555
   adb shell pm grant com.devtool.usbmode android.permission.WRITE_SECURE_SETTINGS
   ```
3. กลับมาที่แอปแล้วกด **Refresh**

สิทธิ์นี้อยู่ถาวรจนกว่าจะถอนแอป

## สิ่งที่แอปทำได้

| ส่วน | ไม่มี root | มี root (`su`) |
|---|---|---|
| อ่าน Developer settings | ได้ | ได้ |
| แก้ Developer settings | ได้ ถ้า grant สิทธิ์แล้ว | ได้ ถ้า grant สิทธิ์แล้ว |
| ดู USB role ปัจจุบัน | ได้ ถ้า SELinux ยอมให้อ่าน sysfs | ได้ |
| สลับ Host/Device | ไม่ได้ แอปพาไปที่ toggle ของระบบหรือของผู้ผลิตแทน | ได้ ปุ่มจะแสดงขึ้นเอง |

## Path USB ที่ตรวจ

- `/sys/devices/platform/ff770000.syscon/ff770000.syscon:usb2-phy@e450/otg_mode` (และ `@e460`)
- `/sys/kernel/debug/usb@fe800000/rk_usb_force_mode` (และ `fe900000`)
- `/sys/bus/platform/drivers/usb20_otg/force_usb_mode`
- `/sys/class/typec/port{0,1}/data_role`
- ไฟล์ `otg_mode` อื่นๆ ที่พบใต้ `/sys/devices/platform`

ถ้าแอปหา path ไม่เจอหรือ role ผิด ให้กด **Copy diagnostics** แล้วส่งข้อความนั้นกลับมาให้ปรับ

## Build ใหม่

ต้องใช้ JDK 17 และ Android SDK 34

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug
```

## โครงสร้างโค้ด

- `DevSettings.kt`: รายการ setting และการอ่าน/เขียน `Settings.Global` / `System`
- `UsbMode.kt`: อ่าน sysfs, ตรวจหาแอปของผู้ผลิต, สลับโหมดด้วย root, สร้างข้อความ diagnostics
- `Shell.kt`: `getprop` / `su` แบบมี timeout
- `MainActivity.kt`: UI แบบ Compose แนวนอน 2 คอลัมน์
