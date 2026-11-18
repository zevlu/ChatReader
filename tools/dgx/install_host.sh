#!/bin/bash
# CoDriver A 台（遊戲台）主機層安裝：不用 Docker、不用 sudo，可以重複執行（已經裝好的步驟會跳過）。
# 事前（手動，見 tools/dgx/README.md）：Sunshine、Steam snap＋登入、BeamNG 遊戲檔放進 Steam 資料夾、Steam 執行過一次。
# 這個腳本做：
#   1. 檢查 BeamNG 遊戲檔，Linux 執行檔加執行權限
#   2. FEX：BeamNG 專用設定（TSO 關閉）＋「使用我的設定」標記（Steam 啟動時就不會跳出詢問視窗）
#   3. BeamNG.Earth（原始碼版）＋ ARM 原生 Python venv ＋ 網路修正 ＋ Rust 原生模組 earthfast
#   4. CoDriver 的 BeamNG mod（遠端指令＋FPS 記錄）
set -e
HERE=$(dirname "$(readlink -f "$0")")
source "$HERE/codriver-dgx.conf"
say() { echo; echo "== $*"; }
mkdir -p "$CODRIVER_HOME/logs"

say "1. BeamNG 遊戲檔"
if [ ! -f "$GAME_DIR/BinLinux/BeamNG.drive.x64" ]; then
  echo "找不到 $GAME_DIR/BinLinux/BeamNG.drive.x64"
  echo "請先把 BeamNG.drive 資料夾和 appmanifest_284160.acf 放進 $STEAM_ROOT/steamapps/（見 README）"; exit 1
fi
chmod +x "$GAME_DIR"/BinLinux/BeamNG.drive.x64 "$GAME_DIR"/BinLinux/console.x64 "$GAME_DIR"/BinLinux/crashReporter "$GAME_DIR"/BinLinux/*.sh
grep -o '"version": *"[^"]*"' "$GAME_DIR/integrity.json" | head -1 || true

say "2. FEX 設定（Steam snap）"
SUC=$HOME/snap/steam/common
if [ ! -d "$SUC/fex_config/AppConfig" ]; then
  echo "找不到 $SUC/fex_config：請先在桌面啟動一次 Steam（第一次會問設定，選 Use default config）"; exit 1
fi
cat > "$SUC/fex_config/AppConfig/BeamNG.drive.x64.json" <<'EOF'
{
  "Comment": "CoDriver: BeamNG runs faster without TSO emulation (2026-11-16 measurements)",
  "Config": {
    "TSOEnabled": "0"
  }
}
EOF
touch "$SUC/prefer_custom_user_config"        # 等於在「Use config from latest update?」選 Use my config
echo "OK：AppConfig/BeamNG.drive.x64.json、prefer_custom_user_config"

say "3. BeamNG.Earth $EARTH_VERSION"
if [ ! -d "$EARTH_DIR/.git" ]; then
  git clone --depth 1 --branch "$EARTH_VERSION" https://github.com/TecnaGamer/BeamNG.Earth.git "$EARTH_DIR"
else
  echo "已有 $EARTH_DIR（$(cat "$EARTH_DIR/VERSION")）"
fi
if [ ! -x "$EARTH_VENV/bin/python" ]; then
  python3 -m venv "$EARTH_VENV"
  "$EARTH_VENV/bin/pip" install -q --upgrade pip || echo "（沒網路，跳過 pip 升級）"
fi
# 會場沒網路：事先在有網路的 DGX 執行 pip download -r server/requirements.txt -d $EARTH_DIR/wheels（DEPLOY_CHECKLIST），
# 這裡看到 wheels/ 就只從它安裝，不連網路
if [ -d "$EARTH_DIR/wheels" ]; then
  echo "從 $EARTH_DIR/wheels 離線安裝 Python 套件"
  "$EARTH_VENV/bin/pip" install -q --no-index --find-links "$EARTH_DIR/wheels" -r "$EARTH_DIR/server/requirements.txt"
else
  "$EARTH_VENV/bin/pip" install -q -r "$EARTH_DIR/server/requirements.txt"
fi
# 學校網路固定連不上 GitHub 部分 IP：每個 IP 最多等 4 秒就換下一個（.pth 一定會被載入；sitecustomize.py 會被 Ubuntu 的蓋掉）
SPK=$("$EARTH_VENV/bin/python" -c "import site; print(site.getsitepackages()[0])")
cp "$HERE/codriver_netfix.py" "$SPK/codriver_netfix.py"
echo "import codriver_netfix" > "$SPK/codriver_netfix.pth"
"$EARTH_VENV/bin/python" -c "import socket; assert socket.create_connection.__module__ == 'codriver_netfix'; print('網路修正 OK')"
if [ ! -f "$EARTH_DIR/server/earthfast.abi3.so" ]; then
  if ! command -v cargo > /dev/null && [ ! -x "$HOME/.cargo/bin/cargo" ]; then
    echo "安裝 Rust（只裝在使用者資料夾 ~/.cargo、~/.rustup）..."
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs -o "$CODRIVER_HOME/rustup-init.sh"
    sh "$CODRIVER_HOME/rustup-init.sh" -y --profile minimal --no-modify-path
  fi
  echo "編譯 earthfast（ARM 原生，約 1～2 分鐘）..."
  (cd "$EARTH_DIR" && PATH=$HOME/.cargo/bin:$PATH PYO3_PYTHON="$EARTH_VENV/bin/python" "$EARTH_VENV/bin/python" tools/build_native.py)
fi
file "$EARTH_DIR/server/earthfast.abi3.so" | grep -q aarch64 && echo "earthfast OK（aarch64）"

say "4. CoDriver 的 BeamNG mod"
mkdir -p "$BEAMNG_USER/mods/unpacked"
# 直接覆蓋，不要先刪資料夾：遊戲執行中刪掉的話，mod 的掛載會失效（擴充載入失敗，要重開遊戲）
mkdir -p "$BEAMNG_USER/mods/unpacked/codriver"
cp -r "$HERE/beamng_mod/codriver/." "$BEAMNG_USER/mods/unpacked/codriver/"
echo "OK：$BEAMNG_USER/mods/unpacked/codriver"

say "完成。啟動：$HERE/start_codriver.sh　停止：$HERE/stop_codriver.sh　操作：$HERE/bng"
