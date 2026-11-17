import type { Metadata } from "next";
import { Suspense } from "react";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { getSessionCookieName } from "@/lib/auth/cookies";
import { decryptSession } from "@/lib/auth/session";

export const metadata: Metadata = {
  title: "Dashboard | Homestay PMS Staff Portal",
  description: "Homestay PMS Staff Dashboard",
};

async function DashboardAuthGuard({ children }: { children: React.ReactNode }) {
  const cookieStore = await cookies();
  const sessionCookieName = getSessionCookieName();
  const sessionCookie = cookieStore.get(sessionCookieName);

  if (!sessionCookie?.value) {
    redirect("/login");
  }

  const session = await decryptSession(sessionCookie.value);
  if (!session || !session.staff?.id) {
    redirect("/login");
  }

  return <>{children}</>;
}

/**
 * Server-side session verification guard for all dashboard routes.
 * Uses Suspense boundary compatible with Next.js cacheComponents & partialPrefetching.
 */
export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <Suspense fallback={<div className="min-h-screen bg-[#f5f6f3]" />}>
      <DashboardAuthGuard>{children}</DashboardAuthGuard>
    </Suspense>
  );
}
