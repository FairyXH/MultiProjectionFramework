# 阶段 1：系统文件提取记录

## 提取时间
2026-10-11 15:48–15:49

## 设备
OPPO PJV110（OnePlus PKG110），Android 15（SDK 35），ColorOS `PKG110_15.0.0.802(CN01)`，
fingerprint `OnePlus/PKG110/OP5D2BL1:15/UKQ1.231108.001/V.1d9a5f9_1-40496:user/release-keys`。

## 提取方式
- 通过 root shell（`super_admin:shell`）直接从 `/system`、`/system_ext` 只读复制（`cp`），
  **不修改任何原系统文件**。
- 复制到 `/sdcard/Download/MultiProjectionFramework/extracted/`（工作目录，同时是
  `/home/projects/MultiProjectionFramework/extracted/` 在 Ubuntu 侧的映射）。

## 文件清单（manifest.txt）
| 文件 | 原始路径 | 大小(B) | SHA-256 | DEX数 |
|---|---|---|---|---|
| framework.jar | /system/framework/framework.jar | 45893681 | f3b499378c610eb6d2376d37fb44891b1566df8bbb8deb3299cf36ab7a1ade75 | 5 |
| oplus-framework.jar | /system/framework/oplus-framework.jar | 9039833 | cd868de105d4edeeaf96cd0af4deb18bec2d36d317de3b793981c65b000ba8e8 | 1 |
| oplus-services.jar | /system/framework/oplus-services.jar | 21043207 | 85e898e08784f8cf39dcbaab822569eb6174aad8bc7a38dce6d4a80493e1093a | 2 |
| services.jar | /system/framework/services.jar | 36913761 | 9fabab5c3960f8543d002f1a05352a6fd6647999ccd2412b42049b2a187bd859 | 4 |
| SystemUI.apk | /system_ext/priv-app/SystemUI/SystemUI.apk | 84027637 | 9db4c35aafde77585cbf7319f020dd718b651711ba849f47c57aa0c6d49155a9 | 6 |

## odex/vdex（备用，未用于主反编译）
| 文件 | 大小(B) | SHA-256 |
|---|---|---|
| services.ocomp.odex | 15451816 | 1b07a911a3a6c7ccfffe17ad4743c93035e3da1b490fa4a46c47cc5d21684a23 |
| services.ocomp.vdex | 414832 | f659caca1848801ef33d5c0d5c15118eb57f507f773cf841f2b13d62af455589 |
| oplus-services.ocomp.odex | 13810792 | 50f7a89c347f5f47b26b1c69aa2db9fdb7a737baf8d664f9aa7e0b25b04c0d07 |
| oplus-services.ocomp.vdex | 208744 | b09edbc595a224658b42a025d7d5143978c47736ad2a53a66f762d3204a5cfc2 |
| SystemUI.odex | 18153632 | 25c663dd2dad4d8010b9ef8f0149962a9942d1b69ed1b1913449db07170aec0c |
| SystemUI.vdex | 1077800 | 44bd72b1796c79eb7c7336e282a6cf1bf2fbbc17df474b9bcb190cf41f2e9546 |

## 关键结论
1. **services.jar / framework.jar / oplus-services.jar / SystemUI.apk 均为完整 DEX 打包，不是空壳**，
   可直接用 jadx 反编译，无需从 odex/vdex 还原。
2. oplus-services.jar（ColorOS 服务扩展）含 2 个 DEX，oplus-framework.jar 含 1 个 DEX，
   是 Oplus/ColorOS 对系统服务的 ExtImpl/Feature 扩展所在，重点检索对象。
3. SystemUI.apk 84MB（6 个 classes*.dex，每个 7–9MB），反编译时需注意内存与时间。

## 未提取到
- `/vendor/framework/` 为空（目录不存在），无 vendor 侧 framework 扩展。
- `/opus/framework`（/oplus/framework）不存在。
- 未提取 `/system/framework/boot-framework.vdex` 等 boot image（services/framework jar 已含完整 DEX，无需）。