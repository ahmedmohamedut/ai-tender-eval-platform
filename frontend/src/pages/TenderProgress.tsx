import { Navigate, useParams } from "react-router";

/** The progress board now lives on the tender overview (PG-06); the old address still works. */
export function TenderProgress() {
  const { tenderId } = useParams();
  return <Navigate replace to={`/tenders/${tenderId}`} />;
}
