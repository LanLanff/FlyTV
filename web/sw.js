// FlyTV service worker：仅为可安装性，不做任何缓存（透传），避免更新后看到旧页面
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));
self.addEventListener('fetch', () => { /* 交给浏览器默认处理，不拦截 */ });
