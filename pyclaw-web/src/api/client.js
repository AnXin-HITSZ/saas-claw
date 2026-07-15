const BASE_URL = "";

async function request(path, options = {}) {
  const token = localStorage.getItem("pyclaw.token");
  const headers = {
    "Content-Type": "application/json",
    ...options.headers,
  };
  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }

  const res = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers,
  });

  if (res.status === 401) {
    localStorage.removeItem("pyclaw.token");
    localStorage.removeItem("pyclaw.user");
    if (window.location.pathname !== "/login" && window.location.pathname !== "/register" && window.location.pathname !== "/") {
      window.location.href = "/login";
    }
    throw new Error("Unauthorized");
  }

  if (res.status === 204) {
    return null;
  }

  const contentType = res.headers.get("content-type") || "";
  if (!contentType.includes("application/json")) {
    const text = await res.text();
    throw new Error(text.slice(0, 200) || `Server returned ${res.status} (non-JSON)`);
  }

  const data = await res.json();

  if (!res.ok) {
    throw new Error(data.message || data.error || `Request failed (${res.status})`);
  }

  return data;
}

export const api = {
  get(path) {
    return request(path);
  },
  post(path, body) {
    return request(path, { method: "POST", body: JSON.stringify(body) });
  },
  put(path, body) {
    return request(path, { method: "PUT", body: JSON.stringify(body) });
  },
  delete(path) {
    return request(path, { method: "DELETE" });
  },
};
