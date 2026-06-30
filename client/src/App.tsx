import ChatPage from '@/pages/ChatPage.tsx';
import AppProviders from '@/providers/AppProviders.tsx';

export default function App() {
  return (
    <AppProviders>
      <ChatPage />
    </AppProviders>
  );
}
