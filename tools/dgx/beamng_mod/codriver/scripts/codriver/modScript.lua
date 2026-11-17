-- CoDriver：遊戲啟動時自動載入遠端指令與 FPS 記錄擴充
setExtensionUnloadMode("codriverRemote", "manual")
extensions.load("codriverRemote")
setExtensionUnloadMode("codriverFps", "manual")
extensions.load("codriverFps")
