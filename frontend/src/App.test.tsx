import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { App } from "./App";

const state = { isLoading: false, isAuthenticated: false, error: undefined as Error | undefined };

vi.mock("react-oidc-context", () => ({
  useAuth: () => ({
    ...state,
    user: state.isAuthenticated ? { profile: { email: "a@example.test" } } : undefined,
    signinRedirect: vi.fn(),
    signoutRedirect: vi.fn(),
  }),
}));

describe("App", () => {
  it("offers sign-in to an anonymous visitor", () => {
    state.isAuthenticated = false;
    render(<App />);
    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
  });

  it("shows the signed-in user and a sign-out button", () => {
    state.isAuthenticated = true;
    render(<App />);
    expect(screen.getByText(/a@example.test/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
  });
});
