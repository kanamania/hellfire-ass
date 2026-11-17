import { api } from '@/lib/api';
import { LibraryApp } from '@/lib/library-app';

export default async function LibraryPage() {
  await api('/api/overview');
  return <LibraryApp />;
}
