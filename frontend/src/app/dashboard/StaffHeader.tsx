"use client";

import React, { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import type { StaffUser } from "@/lib/auth/types";

interface StaffHeaderProps {
  initialStaff?: StaffUser | null;
}

export function StaffHeader({ initialStaff }: StaffHeaderProps) {
  const router = useRouter();
  const [staff, setStaff] = useState<StaffUser | null>(initialStaff ?? null);
  const [loggingOut, setLoggingOut] = useState(false);

  useEffect(() => {
    if (!initialStaff) {
      let active = true;
      fetch("/api/auth/session", { cache: "no-store" })
        .then((res) => (res.ok ? res.json() : null))
        .then((data) => {
          if (active && data?.authenticated && data.user) {
            setStaff(data.user);
          }
        })
        .catch(() => {});
      return () => {
        active = false;
      };
    }
  }, [initialStaff]);

  async function handleLogout() {
    if (loggingOut) return;
    setLoggingOut(true);
    try {
      await fetch("/api/auth/logout", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-PMS-Client": "staff-web",
        },
      });
    } catch {
      // Best-effort logout
    } finally {
      if (typeof window !== "undefined") {
        try {
          window.sessionStorage.removeItem("pms_creation_recovery");
        } catch {
          // ignore storage error
        }
      }
      router.push("/login");
      router.refresh();
    }
  }

  const displayName = staff
    ? `${staff.firstName} ${staff.lastName}`.trim() || staff.email
    : "Staff Member";

  const initials = staff
    ? `${staff.firstName?.[0] || ""}${staff.lastName?.[0] || ""}`.toUpperCase() ||
      staff.email[0].toUpperCase()
    : "S";

  return (
    <div className="flex items-center gap-3">
      <div className="flex items-center gap-2.5">
        <div className="flex h-9 w-9 items-center justify-center rounded-full bg-[#294638] font-semibold text-xs text-[#f8f6f0] shadow-sm">
          {initials}
        </div>
        <div className="hidden sm:block text-left">
          <p className="text-sm font-semibold leading-none text-[#1c352a]">
            {displayName}
          </p>
          <p className="mt-1 text-xs text-[#777b6f] leading-none">
            {staff?.email || "Authenticated Staff"}
          </p>
        </div>
      </div>

      <button
        type="button"
        onClick={handleLogout}
        disabled={loggingOut}
        title="Sign out of your session"
        className="rounded-full border border-[#dce2d9] bg-white px-3.5 py-1.5 text-xs font-medium text-[#435448] transition hover:border-[#b4beaf] hover:bg-[#f5f6f3] disabled:opacity-50"
      >
        {loggingOut ? "Signing out…" : "Sign out"}
      </button>
    </div>
  );
}
