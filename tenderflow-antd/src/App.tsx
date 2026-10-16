import { Navigate, Route, Routes } from 'react-router-dom';
import AppLayout from './layout/AppLayout';
import TenderShell from './pages/TenderShell';
import Overview from './pages/Overview';
import Documents from './pages/Documents';
import Requirements from './pages/Requirements';
import Screening from './pages/Screening';
import Evaluation from './pages/Evaluation';
import Report from './pages/Report';
import TendersList from './pages/TendersList';
import TenderForm from './pages/TenderForm';
import VendorsList from './pages/VendorsList';
import VendorDetail from './pages/VendorDetail';
import VendorForm from './pages/VendorForm';
import { Audit, Categories, Jobs, Notifications, SignedOut, Users } from './pages/Admin';

export default function AppRoutes() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<Navigate to="/tenders" replace />} />
        <Route path="tenders" element={<TendersList />} />
        <Route path="tenders/new" element={<TenderForm />} />
        <Route path="tenders/draft/:id" element={<TenderForm />} />
        <Route path="tenders/:tid" element={<TenderShell />}>
          <Route index element={<Navigate to="overview" replace />} />
          <Route path="overview" element={<Overview />} />
          <Route path="documents" element={<Documents />} />
          <Route path="requirements" element={<Requirements />} />
          <Route path="screening" element={<Screening />} />
          <Route path="evaluation" element={<Evaluation />} />
          <Route path="report" element={<Report />} />
        </Route>
        <Route path="vendors" element={<VendorsList />} />
        <Route path="vendors/new" element={<VendorForm />} />
        <Route path="vendors/:id" element={<VendorDetail />} />
        <Route path="vendors/:id/edit" element={<VendorForm />} />
        <Route path="jobs" element={<Jobs />} />
        <Route path="notifications" element={<Notifications />} />
        <Route path="users" element={<Users />} />
        <Route path="categories" element={<Categories />} />
        <Route path="audit" element={<Audit />} />
        <Route path="signed-out" element={<SignedOut />} />
        <Route path="*" element={<Navigate to="/tenders" replace />} />
      </Route>
    </Routes>
  );
}
