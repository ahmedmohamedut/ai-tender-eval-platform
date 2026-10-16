import { StrictMode, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { HashRouter } from 'react-router-dom';
import { App as AntApp, ConfigProvider } from 'antd';
import { StoreProvider } from './store';
import { buildTheme } from './theme';
import AppRoutes from './App';

function Root() {
  const [mode, setMode] = useState<'light' | 'dark'>('light');
  const [dir, setDir] = useState<'ltr' | 'rtl'>('ltr');
  const theme = useMemo(() => buildTheme(mode), [mode]);
  return (
    <ConfigProvider theme={theme} direction={dir}>
      <AntApp>
        <StoreProvider themeMode={mode} setThemeMode={setMode} direction={dir} setDirection={setDir}>
          <HashRouter><AppRoutes /></HashRouter>
        </StoreProvider>
      </AntApp>
    </ConfigProvider>
  );
}
createRoot(document.getElementById('root')!).render(<StrictMode><Root /></StrictMode>);
