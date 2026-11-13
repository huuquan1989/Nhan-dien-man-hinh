# ScreenWatcher (Android)

Quét **chữ** (OCR offline, ML Kit) hoặc **hình ảnh mẫu** (template matching) trong **một hoặc nhiều vùng** bạn chọn.
Khi khớp → chạy **macro** (chạm vào vị trí tìm thấy, chạm toạ độ cố định, vuốt, chờ, Back/Home...).

## Build
1. Cài Android Studio (Koala trở lên), mở thư mục `ScreenWatcher`, chờ Gradle sync (cần mạng).
2. Cắm điện thoại (Android 8.0+), bật Gỡ lỗi USB, bấm Run.
   Hoặc: `Build > Build APK(s)` rồi cài `app-debug.apk`.

## Cách dùng
1. Mở app, làm lần lượt 3 bước cấp quyền:
   1. Hiển thị trên ứng dụng khác
   2. Bật Dịch vụ trợ năng "ScreenWatcher" (cho phép chạm tự động)
   3. Cấp quyền chụp màn hình
2. **+ Thêm luật**:
   - Kiểu **Chữ**: nhập chữ cần tìm (dùng `|` để thêm lựa chọn, vd `Bỏ qua|Skip`). So khớp không phân biệt hoa/thường và dấu.
   - Kiểu **Hình ảnh**: bấm *Chọn ảnh mẫu trên màn hình*, khoanh đúng hình cần nhận diện.
   - *Chọn vùng*: kéo trên màn hình thật để khoanh vùng cần quét (thêm được nhiều vùng). Nút ✋ cho phép bạn chuyển sang app khác rồi ✏ để vẽ tiếp.
   - *Macro*, ví dụ:
     ```
     tap_match
     wait 500
     tap 540 1800
     ```
   - Bấm **Thử luật** để kiểm tra khớp hay không (chưa chạy macro), rồi **Lưu**.
3. Ở màn hình chính bật **Bật theo dõi**. Dừng bằng công tắc hoặc nút "Dừng hẳn" ở thông báo.

## Lưu ý
- Android 14+ hỏi lại quyền chụp màn hình mỗi lần khởi động dịch vụ.
- Một số app (ngân hàng, DRM...) chặn chụp màn hình hoặc chặn dịch vụ trợ năng; app đó sẽ hiện màn hình đen / không chạm được.
- Template matching so khớp theo đúng tỉ lệ pixel: chọn ảnh mẫu trên chính thiết bị này, nên chọn hình có nhiều chi tiết, không quá nhỏ, và thu hẹp vùng quét để nhanh hơn.
- Chỉ dùng cho mục đích hợp lệ và tuân thủ điều khoản của ứng dụng/trò chơi bạn tự động hoá.
