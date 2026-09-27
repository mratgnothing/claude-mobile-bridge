(() => {
  const button = document.getElementById('openButton');
  const message = document.getElementById('message');

  const params = new URLSearchParams(location.hash.replace(/^#/, ''));
  const token = params.get('token') || '';
  const base = location.origin;

  const appUrl = token
    ? `claudecode://connect?url=${encodeURIComponent(base)}&token=${encodeURIComponent(token)}`
    : '';

  function openApp() {
    if (!appUrl) {
      message.textContent = '配对链接缺少访问令牌，请重新从 QQ 打开完整链接。';
      button.disabled = true;
      return;
    }
    message.textContent = '如果没有自动打开，请点击下方按钮。';
    location.href = appUrl;
  }

  button.addEventListener('click', openApp);
  setTimeout(openApp, 350);
})();
