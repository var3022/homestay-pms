"use client";

import React, { Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { sanitizeDashboardRedirect } from "@/lib/auth/csrf";

function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const rawRedirect = searchParams.get("redirect");
  const redirectPath = sanitizeDashboardRedirect(rawRedirect);

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState("");

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!email.trim() || !password) {
      setErrorMessage("Please enter both email and password.");
      return;
    }

    setLoading(true);
    setErrorMessage("");

    try {
      const response = await fetch("/api/auth/login", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-PMS-Client": "staff-web",
        },
        body: JSON.stringify({
          email: email.trim(),
          password,
        }),
      });

      const data = await response.json().catch(() => null);

      if (!response.ok) {
        if (response.status === 401) {
          setErrorMessage("Invalid email or password.");
        } else if (response.status === 429) {
          setErrorMessage("Too many login attempts. Please wait a moment and try again.");
        } else if (data && typeof data === "object" && "error" in data) {
          setErrorMessage(String(data.error));
        } else {
          setErrorMessage("Sign in failed. Please check your connection and try again.");
        }
        return;
      }

      // Successful login - redirect to sanitized dashboard path
      router.push(redirectPath);
      router.refresh();
    } catch {
      setErrorMessage("Cannot connect to the authentication server. Please try again.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="w-full max-w-md rounded-2xl border border-[#e1e6df] bg-white p-8 shadow-sm sm:p-10">
      <div className="mb-8 text-center">
        <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-[#294638] font-serif text-2xl font-bold text-white shadow-inner">
          H.
        </div>
        <h1 className="mt-5 font-serif text-2xl font-semibold text-[#1c352a] sm:text-3xl">
          Staff Portal
        </h1>
        <p className="mt-2 text-sm text-[#777b6f]">
          Sign in to access property management & operations
        </p>
      </div>

      {errorMessage && (
        <div
          role="alert"
          aria-live="assertive"
          className="mb-6 rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800"
        >
          <div className="flex items-center gap-2">
            <span className="text-base font-bold" aria-hidden="true">
              !
            </span>
            <p>{errorMessage}</p>
          </div>
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-5" noValidate>
        <div>
          <label
            htmlFor="email"
            className="block text-xs font-semibold uppercase tracking-wider text-[#435448]"
          >
            Email address
          </label>
          <input
            id="email"
            name="email"
            type="email"
            autoComplete="email"
            required
            disabled={loading}
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="staff@homestay.local"
            className="mt-2 w-full rounded-xl border border-[#dce2d9] bg-[#fbfbfa] px-4 py-3 text-sm text-[#24382e] outline-none transition focus:border-[#294638] focus:bg-white focus:ring-2 focus:ring-[#294638]/10 disabled:opacity-50"
          />
        </div>

        <div>
          <div className="flex items-center justify-between">
            <label
              htmlFor="password"
              className="block text-xs font-semibold uppercase tracking-wider text-[#435448]"
            >
              Password
            </label>
          </div>
          <input
            id="password"
            name="password"
            type="password"
            autoComplete="current-password"
            required
            disabled={loading}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="••••••••"
            className="mt-2 w-full rounded-xl border border-[#dce2d9] bg-[#fbfbfa] px-4 py-3 text-sm text-[#24382e] outline-none transition focus:border-[#294638] focus:bg-white focus:ring-2 focus:ring-[#294638]/10 disabled:opacity-50"
          />
        </div>

        <button
          type="submit"
          disabled={loading}
          className="flex w-full items-center justify-center gap-2 rounded-xl bg-[#294638] px-5 py-3.5 text-sm font-semibold text-white shadow transition hover:bg-[#1f362b] focus:outline-none focus:ring-2 focus:ring-[#294638]/20 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {loading ? (
            <>
              <svg
                className="h-4 w-4 animate-spin text-white"
                xmlns="http://www.w3.org/2000/svg"
                fill="none"
                viewBox="0 0 24 24"
                aria-hidden="true"
              >
                <circle
                  className="opacity-25"
                  cx="12"
                  cy="12"
                  r="10"
                  stroke="currentColor"
                  strokeWidth="4"
                />
                <path
                  className="opacity-75"
                  fill="currentColor"
                  d="M4 12a8 8 0 018-8v8H4z"
                />
              </svg>
              <span>Signing in…</span>
            </>
          ) : (
            <span>Sign in</span>
          )}
        </button>
      </form>

      <div className="mt-8 border-t border-[#f0ede3] pt-6 text-center">
        <Link
          href="/"
          className="text-xs font-medium text-[#777b6f] transition hover:text-[#294638]"
        >
          ← Return to public website
        </Link>
      </div>
    </div>
  );
}

export default function LoginPage() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center bg-[#f8f6f0] px-4 py-12 text-[#24382e]">
      <Suspense
        fallback={
          <div className="text-sm text-[#777b6f]">Loading sign-in form…</div>
        }
      >
        <LoginForm />
      </Suspense>
    </main>
  );
}
