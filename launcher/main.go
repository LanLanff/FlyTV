// FlyTV.exe —— Windows 启动器（托盘小工具）
// 纯标准库 + Windows API，无第三方依赖，编译：go build -ldflags "-H windowsgui -s -w"
package main

import (
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

// ---------- Win32 ----------
var (
	user32   = syscall.NewLazyDLL("user32.dll")
	kernel32 = syscall.NewLazyDLL("kernel32.dll")
	shell32  = syscall.NewLazyDLL("shell32.dll")

	pRegisterClassExW    = user32.NewProc("RegisterClassExW")
	pCreateWindowExW     = user32.NewProc("CreateWindowExW")
	pDefWindowProcW      = user32.NewProc("DefWindowProcW")
	pDestroyWindow       = user32.NewProc("DestroyWindow")
	pPostQuitMessage     = user32.NewProc("PostQuitMessage")
	pGetMessageW         = user32.NewProc("GetMessageW")
	pTranslateMessage    = user32.NewProc("TranslateMessage")
	pDispatchMessageW    = user32.NewProc("DispatchMessageW")
	pFindWindowW         = user32.NewProc("FindWindowW")
	pPostMessageW        = user32.NewProc("PostMessageW")
	pGetCursorPos        = user32.NewProc("GetCursorPos")
	pCreatePopupMenu     = user32.NewProc("CreatePopupMenu")
	pAppendMenuW         = user32.NewProc("AppendMenuW")
	pDestroyMenu         = user32.NewProc("DestroyMenu")
	pTrackPopupMenu      = user32.NewProc("TrackPopupMenu")
	pSetForegroundWindow = user32.NewProc("SetForegroundWindow")
	pLoadImageW          = user32.NewProc("LoadImageW")
	pMessageBoxW         = user32.NewProc("MessageBoxW")
	pShellNotifyIconW    = shell32.NewProc("Shell_NotifyIconW")
	pCreateMutexW        = kernel32.NewProc("CreateMutexW")
	pGetModuleHandleW    = kernel32.NewProc("GetModuleHandleW")
)

const (
	WM_CLOSE      = 0x0010
	WM_NULL       = 0x0000
	WM_LBUTTONUP  = 0x0202
	WM_RBUTTONUP  = 0x0205
	WM_CONTEXTMENU = 0x007B
	WM_APP        = 0x8000
	WM_TRAY       = WM_APP + 1
	WM_CMD_RESTART = WM_APP + 2

	NIF_MESSAGE = 0x1
	NIF_ICON    = 0x2
	NIF_TIP     = 0x4
	NIM_ADD     = 0x0
	NIM_MODIFY  = 0x1
	NIM_DELETE  = 0x2

	MF_STRING = 0x0
	MF_SEPARATOR = 0x800

	TPM_RETURNCMD   = 0x0100
	TPM_RIGHTBUTTON = 0x0002

	LR_LOADFROMFILE = 0x0010
	LR_DEFAULTSIZE  = 0x0040
	IMAGE_ICON      = 1

	CREATE_NO_WINDOW = 0x08000000

	ID_OPEN   = 1
	ID_RESTART = 2
	ID_LOG    = 3
	ID_DATA   = 4
	ID_UPDATE = 5
	ID_AUTOSTART = 6
	ID_QUIT   = 7
)

type POINT struct{ X, Y int32 }

type MSG struct {
	Hwnd    syscall.Handle
	Message uint32
	WParam  uintptr
	LParam  uintptr
	Time    uint32
	Pt      POINT
}

type WNDCLASSEX struct {
	CbSize        uint32
	Style         uint32
	LpfnWndProc   uintptr
	CbClsExtra    int32
	CbWndExtra    int32
	HInstance     syscall.Handle
	HIcon         syscall.Handle
	HCursor       syscall.Handle
	HbrBackground syscall.Handle
	LpszMenuName  *uint16
	LpszClassName *uint16
	HIconSm       syscall.Handle
}

type NOTIFYICONDATA struct {
	CbSize          uint32
	HWnd            syscall.Handle
	UID             uint32
	UFlags          uint32
	UCallbackMessage uint32
	HIcon           syscall.Handle
	SzTip           [128]uint16
	DwState         uint32
	DwStateMask     uint32
	SzInfo          [256]uint16
	UVersion        uint32
	SzInfoTitle     [64]uint16
	DwInfoFlags     uint32
	GuidItem        syscall.GUID
	HBalloonIcon    syscall.Handle
}

// ---------- 全局 ----------
var (
	appDir   string
	webPort  int
	engineMu sync.Mutex
	engine   *exec.Cmd
	attached bool // true = 附着在别人已启动的引擎上（不是本启动器拉起的）
	hwnd     syscall.Handle
	className = "FlyTVLauncherWnd"
	classTitle = "FlyTV"
)

func logf(f string, a ...interface{}) {
	dir := filepath.Join(appDir, "logs")
	os.MkdirAll(dir, 0755)
	fh, err := os.OpenFile(filepath.Join(dir, "launcher.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
	if err != nil {
		return
	}
	defer fh.Close()
	fmt.Fprintf(fh, "%s %s\n", time.Now().Format("2006-01-02 15:04:05"), fmt.Sprintf(f, a...))
}

func exePath() string {
	p, _ := os.Executable()
	return p
}

func portFile() string { return filepath.Join(appDir, "logs", "port.txt") }

func writePort(p int) {
	os.MkdirAll(filepath.Join(appDir, "logs"), 0755)
	os.WriteFile(portFile(), []byte(strconv.Itoa(p)), 0644)
}

func readPort() int {
	b, err := os.ReadFile(portFile())
	if err != nil {
		return 0
	}
	n, _ := strconv.Atoi(strings.TrimSpace(string(b)))
	return n
}

func clearPort() { os.Remove(portFile()) }

func webURL() string { return fmt.Sprintf("http://127.0.0.1:%d/web", webPort) }

func findFreePort(a, b int) int {
	for p := a; p <= b; p++ {
		ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", p))
		if err == nil {
			ln.Close()
			return p
		}
	}
	return a
}

func engineAlive(port int) bool {
	c := &http.Client{Timeout: 1200 * time.Millisecond}
	resp, err := c.Get(fmt.Sprintf("http://127.0.0.1:%d/api/update/status", port))
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode == 200
}

// 扫描已运行的引擎（用户可能用 启动.cmd 起的）
func findRunningPort() int {
	for p := 9978; p <= 9998; p++ {
		if engineAlive(p) {
			return p
		}
	}
	return 0
}

func startEngine(port int) error {
	engineMu.Lock()
	defer engineMu.Unlock()
	java := filepath.Join(appDir, "jre", "bin", "javaw.exe")
	if _, err := os.Stat(java); err != nil {
		return fmt.Errorf("缺少 jre\\bin\\javaw.exe")
	}
	jar := filepath.Join(appDir, "flytv-engine.jar")
	if _, err := os.Stat(jar); err != nil {
		return fmt.Errorf("缺少 flytv-engine.jar")
	}
	args := []string{
		"-Dfile.encoding=UTF-8",
		fmt.Sprintf("-Dflytv.port=%d", port),
		"-cp", filepath.Join(appDir, "libs") + "\\*;" + jar,
		"dev.flytv.engine.Main",
	}
	cmd := exec.Command(java, args...)
	cmd.Dir = appDir
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: CREATE_NO_WINDOW}
	logsDir := filepath.Join(appDir, "logs")
	os.MkdirAll(logsDir, 0755)
	var out *os.File
	if f, err := os.OpenFile(filepath.Join(logsDir, "engine-out.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644); err == nil {
		out = f
		cmd.Stdout = f
		cmd.Stderr = f
	}
	if err := cmd.Start(); err != nil {
		if out != nil {
			out.Close()
		}
		return err
	}
	engine = cmd
	logf("引擎启动 pid=%d port=%d", cmd.Process.Pid, port)
	go func(c *exec.Cmd, f *os.File) {
		c.Wait()
		if f != nil {
			f.Close()
		}
	}(cmd, out)
	return nil
}

func pidByPort(port int) int {
	out, err := exec.Command("netstat", "-ano", "-p", "TCP").Output()
	if err != nil {
		return 0
	}
	suffix := fmt.Sprintf(":%d", port)
	for _, line := range strings.Split(string(out), "\n") {
		f := strings.Fields(line)
		if len(f) >= 5 && strings.HasSuffix(f[1], suffix) && strings.EqualFold(f[3], "LISTENING") {
			if n, err := strconv.Atoi(f[4]); err == nil {
				return n
			}
		}
	}
	return 0
}

func killTree(pid int) {
	if pid <= 0 {
		return
	}
	exec.Command("taskkill", "/PID", strconv.Itoa(pid), "/T", "/F").Run()
	logf("已结束进程 pid=%d", pid)
}

func stopEngine() {
	engineMu.Lock()
	c := engine
	engine = nil
	engineMu.Unlock()
	if c != nil && c.Process != nil {
		killTree(c.Process.Pid)
		return
	}
	// 附着模式 / 被更新脚本重启过：按端口找到在跑的服务并结束
	if webPort > 0 && engineAlive(webPort) {
		killTree(pidByPort(webPort))
	}
}

func waitReady(port int, d time.Duration) bool {
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		if engineAlive(port) {
			return true
		}
		time.Sleep(300 * time.Millisecond)
	}
	return false
}

func openURL(u string) { exec.Command("rundll32", "url.dll,FileProtocolHandler", u).Start() }
func openPath(p string) { exec.Command("explorer", p).Start() }

func autoStartOn() bool {
	out, err := exec.Command("reg", "query", `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, "/v", "FlyTV").Output()
	return err == nil && strings.Contains(strings.ToLower(string(out)), "flytv.exe")
}

func toggleAutoStart() {
	if autoStartOn() {
		exec.Command("reg", "delete", `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, "/v", "FlyTV", "/f").Run()
		logf("已关闭开机自启")
	} else {
		exec.Command("reg", "add", `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, "/v", "FlyTV", "/t", "REG_SZ", "/d", `"`+exePath()+`" --no-browser`, "/f").Run()
		logf("已开启开机自启")
	}
}

func msgBox(text, title string, flags uintptr) {
	t, _ := syscall.UTF16PtrFromString(title)
	m, _ := syscall.UTF16PtrFromString(text)
	pMessageBoxW.Call(0, uintptr(unsafe.Pointer(m)), uintptr(unsafe.Pointer(t)), flags)
}

func loadIcon() syscall.Handle {
	ico := filepath.Join(appDir, "FlyTV.ico")
	if _, err := os.Stat(ico); err == nil {
		p, _ := syscall.UTF16PtrFromString(ico)
		h, _, _ := pLoadImageW.Call(0, uintptr(unsafe.Pointer(p)), IMAGE_ICON, 0, 0, LR_LOADFROMFILE|LR_DEFAULTSIZE)
		if h != 0 {
			return syscall.Handle(h)
		}
	}
	return 0
}

func addTray() {
	ico := loadIcon()
	var d NOTIFYICONDATA
	d.CbSize = uint32(unsafe.Sizeof(d))
	d.HWnd = hwnd
	d.UID = 1
	d.UFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP
	d.UCallbackMessage = WM_TRAY
	d.HIcon = ico
	tip, _ := syscall.UTF16FromString(fmt.Sprintf("FlyTV · 运行中（端口 %d）", webPort))
	copy(d.SzTip[:], tip)
	pShellNotifyIconW.Call(NIM_ADD, uintptr(unsafe.Pointer(&d)))
}

func delTray() {
	var d NOTIFYICONDATA
	d.CbSize = uint32(unsafe.Sizeof(d))
	d.HWnd = hwnd
	d.UID = 1
	pShellNotifyIconW.Call(NIM_DELETE, uintptr(unsafe.Pointer(&d)))
}

func restartEngine() {
	logf("菜单：重启服务")
	if attached {
		logf("附着模式：外部引擎不归本启动器管理，只重新打开网页")
		openURL(webURL())
		return
	}
	stopEngine()
	time.Sleep(400 * time.Millisecond)
	attached = false
	oldPort := webPort
	webPort = 0
	if oldPort > 0 {
		if ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", oldPort)); err == nil {
			ln.Close()
			webPort = oldPort
		}
	}
	if webPort == 0 {
		webPort = findFreePort(9978, 9998)
	}
	if err := startEngine(webPort); err != nil {
		logf("重启失败: %v", err)
		msgBox("重启失败："+err.Error(), "FlyTV", 0x10)
		return
	}
	if !waitReady(webPort, 30*time.Second) {
		logf("重启后引擎未就绪")
	}
	delTray()
	addTray()
	openURL(webURL())
}

func showMenu() {
	var pt POINT
	pGetCursorPos.Call(uintptr(unsafe.Pointer(&pt)))
	hmenu, _, _ := pCreatePopupMenu.Call()
	add := func(id uintptr, text string) {
		t, _ := syscall.UTF16PtrFromString(text)
		pAppendMenuW.Call(hmenu, MF_STRING, id, uintptr(unsafe.Pointer(t)))
	}
	add(ID_OPEN, "打开网页")
	add(ID_RESTART, "重启服务")
	add(ID_LOG, "查看日志")
	add(ID_DATA, "打开数据目录")
	add(ID_UPDATE, "检查更新（打开 Release 页）")
	auto := "开机自动启动：关"
	if autoStartOn() {
		auto = "开机自动启动：开"
	}
	add(ID_AUTOSTART, auto)
	pAppendMenuW.Call(hmenu, MF_SEPARATOR, 0, 0)
	add(ID_QUIT, "退出（停止服务）")
	pSetForegroundWindow.Call(uintptr(hwnd))
	cmd, _, _ := pTrackPopupMenu.Call(hmenu, TPM_RETURNCMD|TPM_RIGHTBUTTON, uintptr(pt.X), uintptr(pt.Y), 0, uintptr(hwnd), 0)
	pDestroyMenu.Call(hmenu)
	pPostMessageW.Call(uintptr(hwnd), WM_NULL, 0, 0)
	switch int(cmd) {
	case ID_OPEN:
		openURL(webURL())
	case ID_RESTART:
		go restartEngine()
	case ID_LOG:
		openPath(filepath.Join(appDir, "logs", "launcher.log"))
	case ID_DATA:
		openPath(filepath.Join(os.Getenv("LOCALAPPDATA"), "TVBox for Windows"))
	case ID_UPDATE:
		openURL("https://github.com/LanLanff/FlyTV/releases/latest")
	case ID_AUTOSTART:
		toggleAutoStart()
	case ID_QUIT:
		quitApp()
	}
}

func quitApp() {
	logf("退出（菜单/关闭）")
	delTray()
	stopEngine()
	clearPort()
	pDestroyWindow.Call(uintptr(hwnd))
	pPostQuitMessage.Call(0)
}

var wndProcCallback = syscall.NewCallback(func(h syscall.Handle, msg uint32, wp, lp uintptr) uintptr {
	switch msg {
	case WM_TRAY:
		switch lp & 0xFFFF {
		case WM_LBUTTONUP:
			openURL(webURL())
		case WM_RBUTTONUP, WM_CONTEXTMENU:
			showMenu()
		}
		return 0
	case WM_CMD_RESTART:
		go restartEngine()
		return 0
	case WM_CLOSE:
		quitApp()
		return 0
	}
	r, _, _ := pDefWindowProcW.Call(uintptr(h), uintptr(msg), wp, lp)
	return r
})

func createWindow() error {
	hInst, _, _ := pGetModuleHandleW.Call(0)
	cls, _ := syscall.UTF16PtrFromString(className)
	ttl, _ := syscall.UTF16PtrFromString(classTitle)
	var wc WNDCLASSEX
	wc.CbSize = uint32(unsafe.Sizeof(wc))
	wc.LpfnWndProc = wndProcCallback
	wc.HInstance = syscall.Handle(hInst)
	wc.LpszClassName = cls
	if r, _, err := pRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc))); r == 0 {
		return fmt.Errorf("RegisterClassEx failed: %v", err)
	}
	h, _, err := pCreateWindowExW.Call(0, uintptr(unsafe.Pointer(cls)), uintptr(unsafe.Pointer(ttl)), 0, 0, 0, 0, 0, 0, 0, hInst, 0)
	if h == 0 {
		return fmt.Errorf("CreateWindowEx failed: %v", err)
	}
	hwnd = syscall.Handle(h)
	return nil
}

func hasArg(args []string, name string) bool {
	for _, a := range args {
		if a == name || strings.HasPrefix(a, name+"=") {
			return true
		}
	}
	return false
}

func argValue(args []string, prefix string) string {
	for _, a := range args {
		if strings.HasPrefix(a, prefix) {
			return a[len(prefix):]
		}
	}
	return ""
}

func findWindow() syscall.Handle {
	cls, _ := syscall.UTF16PtrFromString(className)
	h, _, _ := pFindWindowW.Call(uintptr(unsafe.Pointer(cls)), 0)
	return syscall.Handle(h)
}

func main() {
	runtime.LockOSThread()
	appDir = filepath.Dir(exePath())
	os.MkdirAll(filepath.Join(appDir, "logs"), 0755)
	args := os.Args[1:]
	logf("启动器启动，参数=%v", args)

	// 单实例
	name, _ := syscall.UTF16PtrFromString("Local\\FlyTVLauncherMutex")
	_, _, mutErr := pCreateMutexW.Call(0, 0, uintptr(unsafe.Pointer(name)))
	already := false
	if e, ok := mutErr.(syscall.Errno); ok && e == 183 { // ERROR_ALREADY_EXISTS
		already = true
	}
	if already {
		h := findWindow()
		logf("已有实例在跑（hwnd=%d）", h)
		if hasArg(args, "--quit") {
			if h != 0 {
				pPostMessageW.Call(uintptr(h), WM_CLOSE, 0, 0)
			}
			return
		}
		if hasArg(args, "--restart") {
			if h != 0 {
				pPostMessageW.Call(uintptr(h), WM_CMD_RESTART, 0, 0)
			}
			return
		}
		// 默认：打开网页
		if p := readPort(); p > 0 && engineAlive(p) {
			webPort = p
		} else if p := findRunningPort(); p > 0 {
			webPort = p
		}
		if webPort > 0 {
			openURL(webURL())
		}
		return
	}

	if err := createWindow(); err != nil {
		logf("窗口创建失败: %v", err)
		msgBox("启动失败："+err.Error(), "FlyTV", 0x10)
		return
	}

	// 端口：--port 指定 > 已被占用的引擎 > 空闲端口
	explicit := false
	if v := argValue(args, "--port="); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			webPort = n
			explicit = true
		}
	}
	if !explicit {
		webPort = findRunningPort()
		if webPort > 0 {
			logf("检测到已运行的引擎，端口 %d（附着模式）", webPort)
			attached = true
		}
	}
	if webPort > 0 && engineAlive(webPort) {
		logf("端口 %d 上已有引擎在跑（附着模式）", webPort)
		attached = true
	}
	if webPort == 0 {
		webPort = findFreePort(9978, 9998)
		attached = false
	}
	if !attached {
		if err := startEngine(webPort); err != nil {
			logf("引擎启动失败: %v", err)
			msgBox("启动失败："+err.Error(), "FlyTV", 0x10)
			return
		}
		if !waitReady(webPort, 45*time.Second) {
			logf("引擎启动超时（45 秒）")
			msgBox("引擎启动超时。请查看 logs\\engine-out.log。", "FlyTV", 0x10)
		}
	}

	addTray()
	writePort(webPort)
	if !hasArg(args, "--no-browser") {
		openURL(webURL())
	}
	logf("就绪 port=%d", webPort)

	// 消息循环
	var msg MSG
	for {
		r, _, _ := pGetMessageW.Call(uintptr(unsafe.Pointer(&msg)), 0, 0, 0)
		if int32(r) <= 0 {
			break
		}
		pTranslateMessage.Call(uintptr(unsafe.Pointer(&msg)))
		pDispatchMessageW.Call(uintptr(unsafe.Pointer(&msg)))
	}
	logf("消息循环结束")
}
