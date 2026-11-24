# 原生打字报告小票机

统计内容仍由 `TypingStats.snapshot()`、`StatsReport.text/image()` 提供；不使用 HTML 工作台里的演示数量、日期或用户信息。

- 出票：黑色出纸槽与打印状态灯，打印中点击小票即可撕开；撕开后上下齿边对应，导出的 PNG 齿边外透明。
- 声音：Web Audio 图离线渲染为 PCM，使用原生 SoundPool 播放。打印与撕票音效可在样式页面分别开关，离开前台时停止声音。
- 纸张：米色、黑色、方格纸、白色。方格纸保留网格、顶部英文标识与日期编号。
- 头图：键帽、书签、叶子、铭牌。
- 印章：方章、椭圆章、邮戳、票据章。保留印泥磨损效果；倾角 -30°～30°，浓淡 10%～100%，默认 -14° / 82%。邮戳日期使用真实报告日期。
- 落款：叶子、条形码、声波、文字。编码旁的日期与按键数量来自真实统计；条纹装饰保留原稿图形。

矢量设计位于 `app/src/main/assets/receipt`，由原生 Canvas 渲染；不请求 CDN 或远程字体。独立样式页面提供实时预览，预览与导出共用绘制逻辑，分享、保存使用同一张完整 PNG。主题、头图、印章、落款、角度、浓淡和声音偏好均保存于应用本地。

重新生成音效：`node --experimental-websocket scripts/receipt/render-sounds.cjs`。原始音频代码保存在 `sound-reference.js`。

真机回归入口：`am instrument -w -e mode stats-report-ui com.kongye.keyboard.test/com.kongye.keyboard.OfflineResourcesCheck`。
