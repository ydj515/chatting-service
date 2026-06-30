import AdminPage from '@/pages/AdminPage.tsx';
import AppProviders from '@/providers/AppProviders.tsx';

export default function App() {
  return (
    <AppProviders>
      <AdminPage />
    </AppProviders>
  );
}
