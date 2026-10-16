import { ConfigProvider, App as AntApp } from "antd";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { buildTheme, type Mode } from "../theme";

type Direction = "ltr" | "rtl";

interface AppearanceValue {
  mode: Mode;
  direction: Direction;
  setMode: (m: Mode) => void;
  setDirection: (d: Direction) => void;
}

const AppearanceContext = createContext<AppearanceValue>({
  mode: "light",
  direction: "ltr",
  setMode: () => undefined,
  setDirection: () => undefined,
});

const KEY = "tender.appearance";

/** Per-viewer convenience only: any storage failure falls back to the system setting. */
function readStored(): { mode?: Mode; direction?: Direction } {
  try {
    const raw = window.localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as { mode?: Mode; direction?: Direction }) : {};
  } catch {
    return {};
  }
}

function systemMode(): Mode {
  try {
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  } catch {
    return "light";
  }
}

/** Theme, dark mode and text direction for the whole application (antd ConfigProvider + App). */
export function AppearanceProvider({
  children,
  initialMode,
}: {
  children: ReactNode;
  initialMode?: Mode;
}) {
  const [mode, setModeState] = useState<Mode>(
    () => initialMode ?? readStored().mode ?? systemMode(),
  );
  const [direction, setDirectionState] = useState<Direction>(() => readStored().direction ?? "ltr");

  useEffect(() => {
    document.documentElement.dataset.theme = mode;
    document.documentElement.style.colorScheme = mode;
    document.documentElement.dir = direction;
  }, [mode, direction]);

  const persist = useCallback((next: { mode: Mode; direction: Direction }) => {
    try {
      window.localStorage.setItem(KEY, JSON.stringify(next));
    } catch {
      /* private window or blocked storage: the choice lasts for this page only */
    }
  }, []);

  const value = useMemo<AppearanceValue>(
    () => ({
      mode,
      direction,
      setMode: (m) => {
        setModeState(m);
        persist({ mode: m, direction });
      },
      setDirection: (d) => {
        setDirectionState(d);
        persist({ mode, direction: d });
      },
    }),
    [mode, direction, persist],
  );
  const config = useMemo(() => buildTheme(mode), [mode]);

  return (
    <AppearanceContext.Provider value={value}>
      <ConfigProvider theme={config} direction={direction} virtual={false}>
        <AntApp>{children}</AntApp>
      </ConfigProvider>
    </AppearanceContext.Provider>
  );
}

export function useAppearance(): AppearanceValue {
  return useContext(AppearanceContext);
}
