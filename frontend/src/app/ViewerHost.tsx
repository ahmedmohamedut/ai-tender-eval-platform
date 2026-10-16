import { Modal } from "antd";
import {
  createContext,
  lazy,
  Suspense,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { Loading } from "../components/ui";

// pdf.js is large; it loads when the first document is opened.
const Viewer = lazy(() => import("../pages/Viewer").then((m) => ({ default: m.Viewer })));

interface Target {
  versionId: string;
  passageId: string | null;
}

interface ViewerApi {
  /** Opens one document version in the viewer window, at a passage when one is given. */
  open: (versionId: string, passageId?: string | null) => void;
}

export const ViewerContext = createContext<ViewerApi>({ open: () => undefined });

/** One document viewer for the whole application: a centered window opened from anywhere. */
export function ViewerProvider({ children }: { children: ReactNode }) {
  const [target, setTarget] = useState<Target | null>(null);
  const open = useCallback(
    (versionId: string, passageId?: string | null) =>
      setTarget({ versionId, passageId: passageId ?? null }),
    [],
  );
  const api = useMemo(() => ({ open }), [open]);
  return (
    <ViewerContext.Provider value={api}>
      {children}
      <Modal
        open={target !== null}
        centered
        width={1120}
        title="Document viewer"
        footer={null}
        destroyOnHidden
        onCancel={() => setTarget(null)}
      >
        {target && (
          <Suspense fallback={<Loading label="Opening the document" />}>
            <Viewer
              key={`${target.versionId}:${target.passageId ?? ""}`}
              versionId={target.versionId}
              passage={target.passageId}
            />
          </Suspense>
        )}
      </Modal>
    </ViewerContext.Provider>
  );
}

export function useViewer(): ViewerApi {
  return useContext(ViewerContext);
}
