import { lazy, Suspense, useEffect } from "react";
import {
  BrowserRouter,
  Navigate,
  Route,
  Routes,
  useNavigate,
  useParams,
  useSearchParams,
} from "react-router";
import { ViewerProvider, useViewer } from "./app/ViewerHost";
import { AnnounceProvider } from "./components/ui";
import { Shell } from "./app/Shell";
import { SessionProvider, useSession } from "./app/Session";
import { ApiProvider, unwrap, useApi, type ApiClient } from "./lib/api";
import { Baseline } from "./pages/Baseline";
import { Documents } from "./pages/Documents";
import { Jobs } from "./pages/Jobs";
import { Audit } from "./pages/Audit";
import { Notifications } from "./pages/Notifications";
import { Requirements } from "./pages/Requirements";
import { CrossVendor } from "./pages/CrossVendor";
import { EvaluationOverview } from "./pages/EvaluationOverview";
import { EvaluationSheet } from "./pages/EvaluationSheet";
import { Screening, ScreeningList } from "./pages/Screening";
import { TenderForm } from "./pages/TenderForm";
import { TenderHome } from "./pages/TenderHome";
import { TenderProgress } from "./pages/TenderProgress";
import { TendersList } from "./pages/TendersList";
import { Users } from "./pages/Users";
import { Categories } from "./pages/Categories";
import { VendorDetail, VendorForm, VendorsList } from "./pages/VendorRegistry";
import { EmptyState, Loading } from "./components/ui";

// pdf.js and the report carry most of the weight; they load when the route is opened.
const Report = lazy(() => import("./pages/Report").then((m) => ({ default: m.Report })));

/** An old or shared viewer address: show the document in the viewer window over its tender. */
function OpenViewer() {
  const { versionId } = useParams();
  const [params] = useSearchParams();
  const api = useApi();
  const viewer = useViewer();
  const navigate = useNavigate();
  useEffect(() => {
    let live = true;
    void (async () => {
      try {
        const v = await unwrap(
          api.GET("/document-versions/{documentVersionId}", {
            params: { path: { documentVersionId: versionId! } },
          }),
        );
        const d = await unwrap(
          api.GET("/documents/{documentId}", { params: { path: { documentId: v.document_id } } }),
        );
        if (!live) return;
        void navigate(`/tenders/${d.tender_id}/documents`, { replace: true });
        viewer.open(versionId!, params.get("passage"));
      } catch {
        if (live) void navigate("/tenders", { replace: true });
      }
    })();
    return () => {
      live = false;
    };
  }, [api, versionId, params, viewer, navigate]);
  return <Loading label="Opening the document" />;
}

/** Vendors are part of the Documents & vendors page now; the old address still works. */
function VendorsRedirect() {
  const { tenderId } = useParams();
  return <Navigate replace to={`/tenders/${tenderId}/documents`} />;
}

function Landing() {
  const { me } = useSession();
  return <Navigate replace to={me.user.role === "member" ? "/my-tenders" : "/tenders"} />;
}

/** Routes are the same for every role; each page and the API refuse what a role may not do. */
export function AppRoutes() {
  return (
    <Routes>
      <Route element={<Shell />}>
        <Route index element={<Landing />} />
        <Route path="tenders" element={<TendersList />} />
        <Route path="my-tenders" element={<TendersList mine />} />
        <Route path="tenders/new" element={<TenderForm />} />
        <Route path="tenders/:tenderId" element={<TenderHome />} />
        <Route path="tenders/:tenderId/edit" element={<TenderForm />} />
        <Route path="tenders/:tenderId/progress" element={<TenderProgress />} />
        <Route path="tenders/:tenderId/documents" element={<Documents />} />
        <Route path="tenders/:tenderId/screening" element={<ScreeningList />} />
        <Route path="tenders/:tenderId/vendors" element={<VendorsRedirect />} />
        <Route path="tenders/:tenderId/requirements" element={<Requirements />} />
        <Route path="tenders/:tenderId/vendors/:tenderVendorId/screening" element={<Screening />} />
        <Route path="tenders/:tenderId/evaluation" element={<EvaluationOverview />} />
        <Route
          path="tenders/:tenderId/vendors/:tenderVendorId/evaluation"
          element={<EvaluationSheet />}
        />
        <Route
          path="tenders/:tenderId/requirements/:requirementId/compare"
          element={<CrossVendor />}
        />
        <Route path="tenders/:tenderId/baseline" element={<Baseline />} />
        <Route path="documents/:versionId/view" element={<OpenViewer />} />
        <Route
          path="tenders/:tenderId/report"
          element={
            <Suspense fallback={<Loading label="Loading the report" />}>
              <Report />
            </Suspense>
          }
        />
        <Route path="vendors" element={<VendorsList />} />
        <Route path="vendors/new" element={<VendorForm />} />
        <Route path="vendors/:vendorId" element={<VendorDetail />} />
        <Route path="vendors/:vendorId/edit" element={<VendorForm />} />
        <Route path="jobs" element={<Jobs />} />
        <Route path="notifications" element={<Notifications />} />
        <Route path="audit" element={<Audit />} />
        <Route path="users" element={<Users />} />
        <Route path="categories" element={<Categories />} />
        <Route
          path="*"
          element={
            <EmptyState title="Page not found">
              Check the address, or go back to your tenders.
            </EmptyState>
          }
        />
      </Route>
    </Routes>
  );
}

export function App({ api }: { api: ApiClient }) {
  return (
    <ApiProvider client={api}>
      <AnnounceProvider>
        <BrowserRouter>
          <SessionProvider api={api}>
            <ViewerProvider>
              <AppRoutes />
            </ViewerProvider>
          </SessionProvider>
        </BrowserRouter>
      </AnnounceProvider>
    </ApiProvider>
  );
}
