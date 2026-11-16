import Link from 'next/link';
import { logout } from '../actions';

const links = [['/', 'Overview'], ['/money', 'Money'], ['/tasks', 'Tasks'], ['/inbox', 'Inbox'], ['/media', 'Recordings'], ['/memory', 'Memory'], ['/search', 'Search']];

export default function SiteLayout({ children }: { children: React.ReactNode }) {
  return (
    <>
      <header className="top">
        <div className="inner">
          <Link href="/" className="brand">HellFire <span>Ass</span></Link>
          <nav>{links.map(([href, label]) => <Link key={href} href={href}>{label}</Link>)}</nav>
          <form action={logout}><button className="quiet" type="submit">Sign out</button></form>
        </div>
      </header>
      <main>{children}</main>
    </>
  );
}
