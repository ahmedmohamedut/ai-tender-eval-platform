import { BrowserRouter, Navigate, Route, Routes } from "react-router";
import { AnnounceProvider } from "./components/ui";
import { Shell } from "./app/Shell";
import { SessionProvider, useSession } from "./app/Session";
import { ApiProvider, type ApiClient } from "./lib/api";
import { Baseline } from "./pages/Baseline";
import { Documents } from "./pages/Documents";
import { Jobs } from "./pages/Jobs";
import { Requirements } from "./pages/Requirements";
import { Screening } from "./pages/Screening";
import { TenderForm } from "./pages/TenderForm";
import { TenderHome } from "./pages/TenderHome";
import { TenderProgress } from "./pages/TenderProgress";
import { TendersList } from "./pages/TendersList";
import { Users } from "./pages/Users";
import { Vendors } from "./pages/Vendors";
import { Viewer } from "./pages/Viewer";
import { EmptyState } from "./components/ui";

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
        <Route path="tenders/:tenderId/vendors" element={<Vendors />} />
        <Route path="tenders/:tenderId/requirements" element={<Requirements />} />
        <Route path="tenders/:tenderId/vendors/:tenderVendorId/screening" element={<Screening />} />
        <Route path="tenders/:tenderId/baseline" element={<Baseline />} />
        <Route path="documents/:versionId/view" element={<Viewer />} />
        <Route path="jobs" element={<Jobs />} />
        <Route path="users" element={<Users />} />
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
            <AppRoutes />
          </SessionProvider>
        </BrowserRouter>
      </AnnounceProvider>
    </ApiProvider>
  );
}
