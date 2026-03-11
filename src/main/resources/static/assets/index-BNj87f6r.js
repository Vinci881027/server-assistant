const __vite__mapDeps=(i,m=__vite__mapDeps,d=(m.f||(m.f=["assets/AdminDashboard-DseJ2ei4.js","assets/vendor-vue-DigYjX59.js","assets/vendor-utils-BU-w9I5u.js","assets/vendor-CmHsBc4_.js","assets/rolldown-runtime-Cj0C9Eap.js","assets/AdminDashboard-D0FfC38Y.css"])))=>i.map(i=>d[i]);
import { A as resolveDynamicComponent, C as nextTick, D as openBlock, E as onUnmounted, F as ref, I as unref, L as normalizeClass, M as watch, N as withCtx, O as renderList, P as withDirectives, R as normalizeStyle, S as mergeModels, T as onMounted, _ as createElementBlock, a as createApp, b as createVNode, c as vModelSelect, d as withModifiers, f as Fragment, g as createCommentVNode, h as createBlock, i as Transition, j as useModel, k as renderSlot, l as vModelText, m as createBaseVNode, n as defineStore, p as computed, r as storeToRefs, s as vModelDynamic, t as createPinia, u as withKeys, v as createStaticVNode, w as onBeforeUnmount, x as defineAsyncComponent, y as createTextVNode, z as toDisplayString } from "./vendor-vue-DigYjX59.js";
import { n as axios_default, t as lib_default } from "./vendor-utils-BU-w9I5u.js";
import { t as purify } from "./vendor-CmHsBc4_.js";

//#region \0vite/modulepreload-polyfill.js
(function polyfill() {
	const relList = document.createElement("link").relList;
	if (relList && relList.supports && relList.supports("modulepreload")) return;
	for (const link of document.querySelectorAll("link[rel=\"modulepreload\"]")) processPreload(link);
	new MutationObserver((mutations) => {
		for (const mutation of mutations) {
			if (mutation.type !== "childList") continue;
			for (const node of mutation.addedNodes) if (node.tagName === "LINK" && node.rel === "modulepreload") processPreload(node);
		}
	}).observe(document, {
		childList: true,
		subtree: true
	});
	function getFetchOpts(link) {
		const fetchOpts = {};
		if (link.integrity) fetchOpts.integrity = link.integrity;
		if (link.referrerPolicy) fetchOpts.referrerPolicy = link.referrerPolicy;
		if (link.crossOrigin === "use-credentials") fetchOpts.credentials = "include";
		else if (link.crossOrigin === "anonymous") fetchOpts.credentials = "omit";
		else fetchOpts.credentials = "same-origin";
		return fetchOpts;
	}
	function processPreload(link) {
		if (link.ep) return;
		link.ep = true;
		const fetchOpts = getFetchOpts(link);
		fetch(link.href, fetchOpts);
	}
})();

//#endregion
//#region src/stores/themeStore.js
const useThemeStore = defineStore("theme", () => {
	const theme = ref("dark");
	const isDark = computed(() => theme.value === "dark");
	function initTheme() {
		const saved = localStorage.getItem("theme");
		if (saved === "light" || saved === "dark") theme.value = saved;
		else if (window.matchMedia("(prefers-color-scheme: light)").matches) theme.value = "light";
		applyTheme();
	}
	function toggleTheme() {
		theme.value = theme.value === "dark" ? "light" : "dark";
		localStorage.setItem("theme", theme.value);
		applyTheme();
	}
	function applyTheme() {
		const root = document.documentElement;
		if (theme.value === "dark") {
			root.classList.add("dark");
			root.classList.remove("light");
		} else {
			root.classList.add("light");
			root.classList.remove("dark");
		}
	}
	return {
		theme,
		isDark,
		initTheme,
		toggleTheme
	};
});

//#endregion
//#region src/api/httpClient.js
/**
* Axios HTTP Client Configuration
*
* Features:
* - Automatic CSRF token handling via cookies
* - Credential inclusion for session management
* - Unified error handling
* - Request/Response interceptors
*/
var httpClient = axios_default.create({
	baseURL: "/api",
	timeout: 3e4,
	headers: { "Content-Type": "application/json" },
	withCredentials: true
});
var UNAUTHORIZED_EVENT$1 = "app:unauthorized";
var lastUnauthorizedEventAt = 0;
function notifyUnauthorized() {
	if (typeof window === "undefined") return;
	const now = Date.now();
	if (now - lastUnauthorizedEventAt <= 1e3) return;
	lastUnauthorizedEventAt = now;
	window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT$1));
}
httpClient.interceptors.request.use((config) => {
	return config;
}, (error) => {
	return Promise.reject(error);
});
httpClient.interceptors.response.use((response) => {
	if (response.data && typeof response.data === "object") {
		if ("success" in response.data) return response;
	}
	return response;
}, (error) => {
	if (error.response) {
		const status = error.response.status;
		const data = error.response.data;
		let errorMessage = "Unknown error";
		if (data && data.message) errorMessage = data.message;
		else if (data && data.error && data.error.message) errorMessage = data.error.message;
		switch (status) {
			case 401:
				console.error("Unauthorized - Please login");
				notifyUnauthorized();
				break;
			case 403:
				console.error("Forbidden - Insufficient permissions");
				break;
			case 404:
				console.error("Not Found:", errorMessage);
				break;
			case 500:
				console.error("Server Error:", errorMessage);
				break;
			default: console.error(`HTTP ${status}:`, errorMessage);
		}
		error.message = errorMessage;
	} else if (error.request) {
		console.error("Network Error - No response received");
		error.message = "Network Error - Please check your connection";
	} else console.error("Request Error:", error.message);
	return Promise.reject(error);
});
var httpClient_default = httpClient;

//#endregion
//#region src/api/authApi.js
/**
* Authentication API
*
* Handles user login, logout, and session status
*/
const authApi = {
	async login(username, password) {
		return (await httpClient_default.post("/login", {
			username,
			password
		})).data;
	},
	async logout() {
		return (await httpClient_default.post("/logout")).data;
	},
	async checkStatus() {
		return (await httpClient_default.get("/status")).data;
	}
};

//#endregion
//#region src/constants/index.js
const MODEL_KEY = {
	GPT_OSS_120B: "120b",
	GPT_OSS_20B: "20b"
};
const DEFAULTS = {
	MODEL: MODEL_KEY.GPT_OSS_20B,
	USE_MEMORY: false,
	SERVER_IP: "Loading..."
};

//#endregion
//#region src/api/chatApi.js
/**
* Chat API
*
* Handles AI chat interactions, conversation history, and model management
*/
const chatApi = {
	async getModels() {
		return (await httpClient_default.get("/ai/models")).data;
	},
	async streamChat(params, signal) {
		const getCookie = (name) => {
			const cookies = (document.cookie || "").split(";");
			for (const cookie of cookies) {
				const [rawKey, ...rest] = cookie.trim().split("=");
				if (rawKey === name) return decodeURIComponent(rest.join("="));
			}
			return null;
		};
		const csrfToken = getCookie("XSRF-TOKEN");
		const headers = { "Content-Type": "application/json" };
		if (csrfToken) headers["X-XSRF-TOKEN"] = csrfToken;
		const response = await fetch("/api/ai/stream", {
			method: "POST",
			headers,
			credentials: "include",
			signal,
			body: JSON.stringify({
				message: params?.message ?? "",
				conversationId: params?.conversationId || null,
				model: params?.model || DEFAULTS.MODEL
			})
		});
		if (response.status === 401) notifyUnauthorized();
		return response;
	},
	async getHistory(conversationId, offsetOrOptions = 0, limitArg = 50) {
		const params = { conversationId };
		if (!(typeof offsetOrOptions === "object" && offsetOrOptions !== null)) {
			params.offset = offsetOrOptions;
			params.limit = limitArg;
		} else {
			const { offset = 0, limit = 50, beforeCreatedAt = null, beforeId = null } = offsetOrOptions;
			params.limit = limit;
			if (typeof beforeCreatedAt === "string" && beforeCreatedAt.trim().length > 0 && typeof beforeId === "number" && Number.isFinite(beforeId)) {
				params.beforeCreatedAt = beforeCreatedAt;
				params.beforeId = beforeId;
			} else params.offset = offset;
		}
		return (await httpClient_default.get("/ai/history", { params })).data;
	},
	async clearHistory(conversationId) {
		return (await httpClient_default.delete("/ai/history", { params: { conversationId } })).data;
	},
	async createConversation() {
		return (await httpClient_default.post("/ai/conversations/new")).data;
	},
	async getConversations() {
		return (await httpClient_default.get("/ai/conversations")).data;
	},
	async deleteLastMessages(conversationId, count = 2) {
		return (await httpClient_default.delete("/ai/history/last-messages", { params: {
			conversationId,
			count
		} })).data;
	}
};

//#endregion
//#region src/api/systemApi.js
/**
* System API
*
* Handles system information and monitoring
*/
const systemApi = { async getInfo() {
	return (await httpClient_default.get("/system/info")).data;
} };

//#endregion
//#region src/stores/authStore.js
var UNAUTHORIZED_EVENT = "app:unauthorized";
var unauthorizedListenerRegistered = false;
/**
* Authentication Store
*
* Manages user authentication state and operations
*/
const useAuthStore = defineStore("auth", () => {
	const isLoggedIn = ref(false);
	const currentUser = ref("");
	const isAdmin = ref(false);
	function resetAuthState() {
		isLoggedIn.value = false;
		currentUser.value = "";
		isAdmin.value = false;
	}
	function handleUnauthorized() {
		if (!isLoggedIn.value) return;
		resetAuthState();
	}
	function initializeUnauthorizedInterceptor() {
		if (unauthorizedListenerRegistered || typeof window === "undefined") return;
		window.addEventListener(UNAUTHORIZED_EVENT, handleUnauthorized);
		unauthorizedListenerRegistered = true;
	}
	initializeUnauthorizedInterceptor();
	/**
	* Login with username and password
	* @param {string} username - Linux system username
	* @param {string} password - User password
	* @returns {Promise<{success: boolean, message: string, code?: string, data?: object}>}
	*/
	async function login(username, password) {
		try {
			const result = await authApi.login(username, password);
			if (result.success) {
				isLoggedIn.value = true;
				currentUser.value = result.data.user;
				isAdmin.value = result.data.isAdmin;
			}
			return result;
		} catch (error) {
			console.error("Login error:", error);
			const apiCode = error?.response?.data?.error?.code || null;
			const apiData = error?.response?.data?.data || null;
			return {
				success: false,
				message: error.message || "Login failed",
				code: apiCode,
				data: apiData
			};
		}
	}
	/**
	* Logout current user
	* @returns {Promise<{success: boolean}>}
	*/
	async function logout() {
		try {
			const result = await authApi.logout();
			if (result.success) resetAuthState();
			return result;
		} catch (error) {
			console.error("Logout error:", error);
			resetAuthState();
			return {
				success: false,
				message: error.message
			};
		}
	}
	/**
	* Check current login status
	* @returns {Promise<boolean>} - Whether user is logged in
	*/
	async function checkStatus() {
		try {
			const result = await authApi.checkStatus();
			if (result.success) {
				isLoggedIn.value = true;
				currentUser.value = result.data.user;
				isAdmin.value = result.data.isAdmin;
				return true;
			} else {
				resetAuthState();
				return false;
			}
		} catch (error) {
			console.error("Status check error:", error);
			resetAuthState();
			return false;
		}
	}
	return {
		isLoggedIn,
		currentUser,
		isAdmin,
		resetAuthState,
		initializeUnauthorizedInterceptor,
		login,
		logout,
		checkStatus
	};
});

//#endregion
//#region src/assets/favicon.svg?raw
var favicon_default = "<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"h-8 w-8 text-white\" fill=\"none\" viewBox=\"0 0 24 24\" stroke=\"currentColor\">\n  <path stroke-linecap=\"round\" stroke-linejoin=\"round\" stroke-width=\"1.5\" d=\"M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2m-2-4h.01M17 16h.01\" />\n</svg>\n\n";

//#endregion
//#region \0plugin-vue:export-helper
var __plugin_vue_export_helper_default = (sfc, props) => {
	const target = sfc.__vccOpts || sfc;
	for (const [key, val] of props) target[key] = val;
	return target;
};

//#endregion
//#region src/components/Login.vue
var _hoisted_1$11 = { class: "login-wrapper relative flex min-h-screen items-center justify-center px-6 py-12 overflow-hidden" };
var _hoisted_2$8 = ["aria-label"];
var _hoisted_3$8 = {
	key: 0,
	xmlns: "http://www.w3.org/2000/svg",
	class: "h-5 w-5",
	fill: "none",
	viewBox: "0 0 24 24",
	stroke: "currentColor",
	style: { "color": "var(--accent-warning)" }
};
var _hoisted_4$6 = {
	key: 1,
	xmlns: "http://www.w3.org/2000/svg",
	class: "h-5 w-5",
	fill: "none",
	viewBox: "0 0 24 24",
	stroke: "currentColor",
	style: { "color": "var(--accent-primary)" }
};
var _hoisted_5$5 = {
	class: "login-card rounded-2xl p-8 shadow-2xl backdrop-blur-xl border",
	style: {
		"background-color": "var(--glass-bg)",
		"border-color": "var(--glass-border)"
	}
};
var _hoisted_6$5 = { class: "text-center mb-8" };
var _hoisted_7$5 = {
	class: "inline-flex items-center justify-center w-16 h-16 rounded-2xl mb-4 shadow-lg",
	style: { "background": "linear-gradient(135deg, var(--accent-primary), var(--accent-secondary))" }
};
var _hoisted_8$5 = ["innerHTML"];
var _hoisted_9$5 = { class: "relative" };
var _hoisted_10$5 = { class: "absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none" };
var _hoisted_11$5 = {
	xmlns: "http://www.w3.org/2000/svg",
	class: "h-5 w-5",
	fill: "none",
	viewBox: "0 0 24 24",
	stroke: "currentColor",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_12$4 = { class: "relative" };
var _hoisted_13$4 = { class: "absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none" };
var _hoisted_14$4 = {
	xmlns: "http://www.w3.org/2000/svg",
	class: "h-5 w-5",
	fill: "none",
	viewBox: "0 0 24 24",
	stroke: "currentColor",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_15$4 = ["type"];
var _hoisted_16$4 = [
	"aria-label",
	"title",
	"aria-pressed"
];
var _hoisted_17$4 = {
	key: 0,
	xmlns: "http://www.w3.org/2000/svg",
	class: "h-5 w-5",
	fill: "none",
	viewBox: "0 0 24 24",
	stroke: "currentColor"
};
var _hoisted_18$4 = {
	key: 1,
	xmlns: "http://www.w3.org/2000/svg",
	class: "h-5 w-5",
	fill: "none",
	viewBox: "0 0 24 24",
	stroke: "currentColor"
};
var _hoisted_19$4 = ["disabled"];
var _hoisted_20$4 = {
	key: 0,
	class: "animate-spin -ml-1 mr-3 h-5 w-5 text-white",
	xmlns: "http://www.w3.org/2000/svg",
	fill: "none",
	viewBox: "0 0 24 24"
};
var _sfc_main$11 = {
	__name: "Login",
	emits: ["login-success"],
	setup(__props, { emit: __emit }) {
		const themeStore = useThemeStore();
		const authStore = useAuthStore();
		const emit = __emit;
		const username = ref("");
		const password = ref("");
		const isLoading = ref(false);
		const message = ref("");
		const isError = ref(false);
		const isMounted = ref(false);
		const showPassword = ref(false);
		onMounted(() => {
			requestAnimationFrame(() => isMounted.value = true);
		});
		const formatLockoutMessage = (retryAfterSeconds) => {
			const parsedSeconds = Number(retryAfterSeconds);
			if (!Number.isFinite(parsedSeconds) || parsedSeconds <= 0) return null;
			return `帳號已鎖定，請 ${Math.max(1, Math.ceil(parsedSeconds / 60))} 分鐘後再試`;
		};
		const handleLogin = async () => {
			isLoading.value = true;
			message.value = "";
			isError.value = false;
			try {
				const result = await authStore.login(username.value, password.value);
				if (result.success) {
					message.value = result.message || "驗證成功！正在初始化使用者環境...";
					emit("login-success");
				} else {
					isError.value = true;
					if (result.code === "LOGIN_RATE_LIMITED") {
						const lockoutMessage = formatLockoutMessage(result.data?.retryAfterSeconds);
						if (lockoutMessage) {
							message.value = lockoutMessage;
							return;
						}
					}
					message.value = result.message || result.error?.message || "登入失敗：使用者名稱不存在或密碼錯誤";
				}
			} catch (error) {
				isError.value = true;
				message.value = "連線錯誤：無法連接到後端伺服器";
				console.error("Login error:", error);
			} finally {
				isLoading.value = false;
			}
		};
		return (_ctx, _cache) => {
			return openBlock(), createElementBlock("div", _hoisted_1$11, [
				_cache[20] || (_cache[20] = createBaseVNode("div", { class: "absolute inset-0 login-bg" }, null, -1)),
				_cache[21] || (_cache[21] = createBaseVNode("div", {
					class: "absolute top-1/4 -left-20 w-72 h-72 rounded-full blur-3xl opacity-20",
					style: { "background-color": "var(--accent-primary)" }
				}, null, -1)),
				_cache[22] || (_cache[22] = createBaseVNode("div", {
					class: "absolute bottom-1/4 -right-20 w-80 h-80 rounded-full blur-3xl opacity-15",
					style: { "background-color": "var(--accent-secondary)" }
				}, null, -1)),
				createBaseVNode("button", {
					onClick: _cache[0] || (_cache[0] = ($event) => unref(themeStore).toggleTheme()),
					class: "absolute top-6 right-6 p-2.5 rounded-xl border backdrop-blur-sm transition-all hover:scale-110 z-10",
					style: {
						"background-color": "var(--glass-bg)",
						"border-color": "var(--glass-border)"
					},
					title: "切換主題",
					"aria-label": unref(themeStore).isDark ? "切換為淺色主題" : "切換為深色主題"
				}, [unref(themeStore).isDark ? (openBlock(), createElementBlock("svg", _hoisted_3$8, [..._cache[8] || (_cache[8] = [createBaseVNode("path", {
					"stroke-linecap": "round",
					"stroke-linejoin": "round",
					"stroke-width": "2",
					d: "M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z"
				}, null, -1)])])) : (openBlock(), createElementBlock("svg", _hoisted_4$6, [..._cache[9] || (_cache[9] = [createBaseVNode("path", {
					"stroke-linecap": "round",
					"stroke-linejoin": "round",
					"stroke-width": "2",
					d: "M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z"
				}, null, -1)])]))], 8, _hoisted_2$8),
				createBaseVNode("div", { class: normalizeClass(["relative z-10 w-full max-w-md transition-all duration-700 ease-out", isMounted.value ? "opacity-100 translate-y-0" : "opacity-0 translate-y-4"]) }, [createBaseVNode("div", _hoisted_5$5, [createBaseVNode("div", _hoisted_6$5, [
					createBaseVNode("div", _hoisted_7$5, [createBaseVNode("span", {
						class: "login-favicon-icon",
						innerHTML: unref(favicon_default),
						"aria-hidden": "true"
					}, null, 8, _hoisted_8$5)]),
					_cache[10] || (_cache[10] = createBaseVNode("h1", {
						class: "text-2xl font-bold tracking-tight",
						style: { "color": "var(--text-primary)" }
					}, " CGV Lab Server Assistant ", -1)),
					_cache[11] || (_cache[11] = createBaseVNode("p", {
						class: "mt-2 text-sm",
						style: { "color": "var(--text-tertiary)" }
					}, " 請輸入伺服器系統使用者帳號與密碼 ", -1))
				]), createBaseVNode("form", {
					class: "space-y-5",
					onSubmit: withModifiers(handleLogin, ["prevent"])
				}, [
					createBaseVNode("div", null, [_cache[13] || (_cache[13] = createBaseVNode("label", {
						for: "username",
						class: "block text-sm font-medium mb-2",
						style: { "color": "var(--text-secondary)" }
					}, " 使用者名稱 ", -1)), createBaseVNode("div", _hoisted_9$5, [createBaseVNode("div", _hoisted_10$5, [(openBlock(), createElementBlock("svg", _hoisted_11$5, [..._cache[12] || (_cache[12] = [createBaseVNode("path", {
						"stroke-linecap": "round",
						"stroke-linejoin": "round",
						"stroke-width": "1.5",
						d: "M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"
					}, null, -1)])]))]), withDirectives(createBaseVNode("input", {
						id: "username",
						name: "username",
						type: "text",
						"onUpdate:modelValue": _cache[1] || (_cache[1] = ($event) => username.value = $event),
						autofocus: "",
						required: "",
						class: "block w-full rounded-xl border py-3 pl-11 pr-4 text-sm outline-none transition-all duration-200 placeholder:opacity-50",
						style: {
							"background-color": "var(--bg-input)",
							"color": "var(--text-primary)",
							"border-color": "var(--border-primary)"
						},
						onFocus: _cache[2] || (_cache[2] = ($event) => $event.target.style.borderColor = "var(--accent-primary)"),
						onBlur: _cache[3] || (_cache[3] = ($event) => $event.target.style.borderColor = "var(--border-primary)")
					}, null, 544), [[vModelText, username.value]])])]),
					createBaseVNode("div", null, [_cache[17] || (_cache[17] = createBaseVNode("label", {
						for: "password",
						class: "block text-sm font-medium mb-2",
						style: { "color": "var(--text-secondary)" }
					}, " 密碼 ", -1)), createBaseVNode("div", _hoisted_12$4, [
						createBaseVNode("div", _hoisted_13$4, [(openBlock(), createElementBlock("svg", _hoisted_14$4, [..._cache[14] || (_cache[14] = [createBaseVNode("path", {
							"stroke-linecap": "round",
							"stroke-linejoin": "round",
							"stroke-width": "1.5",
							d: "M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"
						}, null, -1)])]))]),
						withDirectives(createBaseVNode("input", {
							id: "password",
							name: "password",
							type: showPassword.value ? "text" : "password",
							"onUpdate:modelValue": _cache[4] || (_cache[4] = ($event) => password.value = $event),
							required: "",
							class: "block w-full rounded-xl border py-3 pl-11 pr-12 text-sm outline-none transition-all duration-200 placeholder:opacity-50",
							style: {
								"background-color": "var(--bg-input)",
								"color": "var(--text-primary)",
								"border-color": "var(--border-primary)"
							},
							onFocus: _cache[5] || (_cache[5] = ($event) => $event.target.style.borderColor = "var(--accent-primary)"),
							onBlur: _cache[6] || (_cache[6] = ($event) => $event.target.style.borderColor = "var(--border-primary)")
						}, null, 40, _hoisted_15$4), [[vModelDynamic, password.value]]),
						createBaseVNode("button", {
							type: "button",
							class: "absolute inset-y-0 right-0 px-3 flex items-center rounded-r-xl transition-colors hover:opacity-80",
							style: { "color": "var(--text-tertiary)" },
							"aria-label": showPassword.value ? "隱藏密碼" : "顯示密碼",
							title: showPassword.value ? "隱藏密碼" : "顯示密碼",
							"aria-pressed": showPassword.value,
							onClick: _cache[7] || (_cache[7] = ($event) => showPassword.value = !showPassword.value)
						}, [showPassword.value ? (openBlock(), createElementBlock("svg", _hoisted_17$4, [..._cache[15] || (_cache[15] = [createBaseVNode("path", {
							"stroke-linecap": "round",
							"stroke-linejoin": "round",
							"stroke-width": "1.5",
							d: "M15 12a3 3 0 11-6 0 3 3 0 016 0z"
						}, null, -1), createBaseVNode("path", {
							"stroke-linecap": "round",
							"stroke-linejoin": "round",
							"stroke-width": "1.5",
							d: "M2.458 12C3.732 7.943 7.523 5 12 5c4.477 0 8.268 2.943 9.542 7-1.274 4.057-5.065 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
						}, null, -1)])])) : (openBlock(), createElementBlock("svg", _hoisted_18$4, [..._cache[16] || (_cache[16] = [createBaseVNode("path", {
							"stroke-linecap": "round",
							"stroke-linejoin": "round",
							"stroke-width": "1.5",
							d: "M13.875 18.825A10.05 10.05 0 0112 19c-4.477 0-8.268-2.943-9.542-7a9.97 9.97 0 012.065-3.633M6.64 6.635A9.953 9.953 0 0112 5c4.477 0 8.268 2.943 9.542 7a9.97 9.97 0 01-1.88 3.37M15 12a3 3 0 11-6 0 3 3 0 016 0zm-6.364-6.364L3 3m18 18l-3.636-3.636"
						}, null, -1)])]))], 8, _hoisted_16$4)
					])]),
					createBaseVNode("button", {
						type: "submit",
						disabled: isLoading.value,
						class: "login-btn flex w-full justify-center items-center rounded-xl py-3 text-sm font-semibold text-white shadow-lg transition-all duration-200 hover:-translate-y-0.5 hover:shadow-xl disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:translate-y-0"
					}, [isLoading.value ? (openBlock(), createElementBlock("svg", _hoisted_20$4, [..._cache[18] || (_cache[18] = [createBaseVNode("circle", {
						class: "opacity-25",
						cx: "12",
						cy: "12",
						r: "10",
						stroke: "currentColor",
						"stroke-width": "4"
					}, null, -1), createBaseVNode("path", {
						class: "opacity-75",
						fill: "currentColor",
						d: "M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
					}, null, -1)])])) : createCommentVNode("", true), createTextVNode(" " + toDisplayString(isLoading.value ? "驗證中..." : "登入"), 1)], 8, _hoisted_19$4),
					message.value ? (openBlock(), createElementBlock("div", {
						key: 0,
						class: "text-center text-sm font-medium",
						style: normalizeStyle({ color: isError.value ? "var(--accent-danger)" : "var(--accent-success)" })
					}, toDisplayString(message.value), 5)) : createCommentVNode("", true)
				], 32)]), _cache[19] || (_cache[19] = createBaseVNode("p", {
					class: "text-center text-xs mt-6",
					style: { "color": "var(--text-tertiary)" }
				}, " Server Assistant v1.0 ", -1))], 2)
			]);
		};
	}
};
var Login_default = /* @__PURE__ */ __plugin_vue_export_helper_default(_sfc_main$11, [["__scopeId", "data-v-fbe17a8c"]]);

//#endregion
//#region src/components/ModelSwitchToast.vue
var _hoisted_1$10 = {
	key: 0,
	class: "fixed top-6 left-1/2 -translate-x-1/2 z-50 rounded-xl px-4 py-2 text-sm font-medium shadow-lg border",
	style: {
		"background-color": "var(--bg-tertiary)",
		"color": "var(--text-primary)",
		"border-color": "color-mix(in srgb, var(--accent-primary) 35%, var(--border-primary))"
	}
};
var _sfc_main$10 = {
	__name: "ModelSwitchToast",
	props: {
		show: {
			type: Boolean,
			required: true
		},
		message: {
			type: String,
			default: ""
		}
	},
	setup(__props) {
		return (_ctx, _cache) => {
			return openBlock(), createBlock(Transition, { name: "model-switch-toast" }, {
				default: withCtx(() => [__props.show ? (openBlock(), createElementBlock("div", _hoisted_1$10, toDisplayString(__props.message), 1)) : createCommentVNode("", true)]),
				_: 1
			});
		};
	}
};
var ModelSwitchToast_default = /* @__PURE__ */ __plugin_vue_export_helper_default(_sfc_main$10, [["__scopeId", "data-v-12c0d34b"]]);

//#endregion
//#region src/components/NetworkOfflineBanner.vue
var _hoisted_1$9 = {
	key: 0,
	class: "network-offline-banner",
	role: "status",
	"aria-live": "polite"
};
var _sfc_main$9 = {
	__name: "NetworkOfflineBanner",
	props: { show: {
		type: Boolean,
		required: true
	} },
	setup(__props) {
		return (_ctx, _cache) => {
			return openBlock(), createBlock(Transition, { name: "network-banner" }, {
				default: withCtx(() => [__props.show ? (openBlock(), createElementBlock("div", _hoisted_1$9, " 網路連線中斷，恢復後會自動重新載入目前對話歷史。 ")) : createCommentVNode("", true)]),
				_: 1
			});
		};
	}
};
var NetworkOfflineBanner_default = /* @__PURE__ */ __plugin_vue_export_helper_default(_sfc_main$9, [["__scopeId", "data-v-3a35a6fb"]]);

//#endregion
//#region src/components/ShortcutHelpDialog.vue
var _hoisted_1$8 = { class: "flex items-start justify-between gap-3" };
var _sfc_main$8 = {
	__name: "ShortcutHelpDialog",
	props: { isOpen: {
		type: Boolean,
		required: true
	} },
	emits: ["close"],
	setup(__props) {
		return (_ctx, _cache) => {
			return openBlock(), createBlock(Transition, { name: "shortcut-fade" }, {
				default: withCtx(() => [__props.isOpen ? (openBlock(), createElementBlock("div", {
					key: 0,
					class: "fixed inset-0 z-[70] flex items-center justify-center px-4",
					style: { "background-color": "color-mix(in srgb, #000 45%, transparent)" },
					onClick: _cache[2] || (_cache[2] = ($event) => _ctx.$emit("close"))
				}, [createBaseVNode("section", {
					class: "w-full max-w-md rounded-2xl border shadow-2xl p-5",
					style: {
						"background-color": "var(--bg-secondary)",
						"border-color": "var(--border-primary)"
					},
					role: "dialog",
					"aria-modal": "true",
					"aria-label": "快捷鍵說明",
					onClick: _cache[1] || (_cache[1] = withModifiers(() => {}, ["stop"]))
				}, [createBaseVNode("div", _hoisted_1$8, [_cache[3] || (_cache[3] = createBaseVNode("div", null, [createBaseVNode("h2", {
					class: "text-base font-semibold",
					style: { "color": "var(--text-primary)" }
				}, "快捷鍵"), createBaseVNode("p", {
					class: "text-xs mt-1",
					style: { "color": "var(--text-tertiary)" }
				}, "快速操作聊天工作區")], -1)), createBaseVNode("button", {
					class: "text-xs px-2.5 py-1 rounded-lg border",
					style: {
						"border-color": "var(--border-primary)",
						"color": "var(--text-secondary)",
						"background-color": "var(--bg-tertiary)"
					},
					onClick: _cache[0] || (_cache[0] = ($event) => _ctx.$emit("close")),
					"aria-label": "關閉快捷鍵說明"
				}, " 關閉 ")]), _cache[4] || (_cache[4] = createBaseVNode("div", { class: "mt-4 space-y-2" }, [
					createBaseVNode("div", {
						class: "flex items-center justify-between rounded-lg px-3 py-2",
						style: { "background-color": "var(--bg-tertiary)" }
					}, [createBaseVNode("span", {
						class: "text-sm",
						style: { "color": "var(--text-secondary)" }
					}, "新對話"), createBaseVNode("kbd", {
						class: "px-2 py-1 rounded border text-xs font-mono",
						style: {
							"border-color": "var(--border-primary)",
							"color": "var(--text-primary)"
						}
					}, "Ctrl + N")]),
					createBaseVNode("div", {
						class: "flex items-center justify-between rounded-lg px-3 py-2",
						style: { "background-color": "var(--bg-tertiary)" }
					}, [createBaseVNode("span", {
						class: "text-sm",
						style: { "color": "var(--text-secondary)" }
					}, "搜尋對話"), createBaseVNode("kbd", {
						class: "px-2 py-1 rounded border text-xs font-mono",
						style: {
							"border-color": "var(--border-primary)",
							"color": "var(--text-primary)"
						}
					}, "Ctrl + K")]),
					createBaseVNode("div", {
						class: "flex items-center justify-between rounded-lg px-3 py-2",
						style: { "background-color": "var(--bg-tertiary)" }
					}, [createBaseVNode("span", {
						class: "text-sm",
						style: { "color": "var(--text-secondary)" }
					}, "關閉 Sidebar"), createBaseVNode("kbd", {
						class: "px-2 py-1 rounded border text-xs font-mono",
						style: {
							"border-color": "var(--border-primary)",
							"color": "var(--text-primary)"
						}
					}, "Esc")]),
					createBaseVNode("div", {
						class: "flex items-center justify-between rounded-lg px-3 py-2",
						style: { "background-color": "var(--bg-tertiary)" }
					}, [createBaseVNode("span", {
						class: "text-sm",
						style: { "color": "var(--text-secondary)" }
					}, "顯示快捷鍵說明"), createBaseVNode("kbd", {
						class: "px-2 py-1 rounded border text-xs font-mono",
						style: {
							"border-color": "var(--border-primary)",
							"color": "var(--text-primary)"
						}
					}, "Ctrl + /")])
				], -1))])])) : createCommentVNode("", true)]),
				_: 1
			});
		};
	}
};
var ShortcutHelpDialog_default = /* @__PURE__ */ __plugin_vue_export_helper_default(_sfc_main$8, [["__scopeId", "data-v-435ded59"]]);

//#endregion
//#region src/components/UndoDeleteToast.vue
var _hoisted_1$7 = {
	key: 0,
	class: "fixed bottom-6 left-1/2 -translate-x-1/2 z-50 rounded-xl shadow-lg px-4 py-3 flex flex-col gap-2 min-w-[280px] max-w-sm",
	style: {
		"background-color": "var(--bg-tertiary)",
		"border": "1px solid var(--border-primary)"
	}
};
var _hoisted_2$7 = { class: "flex items-center justify-between gap-4" };
var _hoisted_3$7 = {
	class: "text-sm truncate",
	style: { "color": "var(--text-primary)" }
};
var _sfc_main$7 = {
	__name: "UndoDeleteToast",
	props: { pendingDelete: {
		type: Object,
		default: null
	} },
	emits: ["undo", "dismiss"],
	setup(__props) {
		return (_ctx, _cache) => {
			return openBlock(), createBlock(Transition, { name: "toast-slide" }, {
				default: withCtx(() => [__props.pendingDelete ? (openBlock(), createElementBlock("div", _hoisted_1$7, [createBaseVNode("div", _hoisted_2$7, [createBaseVNode("span", _hoisted_3$7, " 已刪除「" + toDisplayString(__props.pendingDelete.title) + "」 ", 1), createBaseVNode("button", {
					onClick: _cache[0] || (_cache[0] = ($event) => _ctx.$emit("undo")),
					class: "shrink-0 text-xs font-semibold px-2.5 py-1 rounded-lg transition-all hover:scale-105",
					style: {
						"background-color": "color-mix(in srgb, var(--accent-primary) 20%, transparent)",
						"color": "var(--accent-primary)"
					}
				}, " 復原 ")]), _cache[1] || (_cache[1] = createBaseVNode("div", {
					class: "h-0.5 rounded-full overflow-hidden",
					style: { "background-color": "var(--border-primary)" }
				}, [createBaseVNode("div", {
					class: "h-full rounded-full toast-countdown",
					style: { "background-color": "var(--accent-primary)" }
				})], -1))])) : createCommentVNode("", true)]),
				_: 1
			});
		};
	}
};
var UndoDeleteToast_default = /* @__PURE__ */ __plugin_vue_export_helper_default(_sfc_main$7, [["__scopeId", "data-v-db6f4089"]]);

//#endregion
//#region src/components/Sidebar.vue
var _hoisted_1$6 = ["inert", "aria-hidden"];
var _hoisted_2$6 = { class: "p-3 flex flex-col gap-2" };
var _hoisted_3$6 = {
	xmlns: "http://www.w3.org/2000/svg",
	class: "h-4 w-4",
	fill: "none",
	viewBox: "0 0 24 24",
	stroke: "currentColor",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_4$5 = { class: "relative" };
var _hoisted_5$4 = {
	xmlns: "http://www.w3.org/2000/svg",
	class: "absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 pointer-events-none",
	fill: "none",
	viewBox: "0 0 24 24",
	stroke: "currentColor",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_6$4 = { class: "flex-1 overflow-y-auto px-2 py-1 custom-scrollbar" };
var _hoisted_7$4 = {
	key: 0,
	class: "px-3 py-8 flex flex-col items-center gap-3 text-center"
};
var _hoisted_8$4 = {
	xmlns: "http://www.w3.org/2000/svg",
	class: "h-10 w-10 opacity-30",
	fill: "none",
	viewBox: "0 0 24 24",
	stroke: "currentColor",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_9$4 = {
	key: 1,
	class: "px-3 py-8 flex flex-col items-center gap-3 text-center"
};
var _hoisted_10$4 = {
	xmlns: "http://www.w3.org/2000/svg",
	class: "h-10 w-10 opacity-30",
	fill: "none",
	viewBox: "0 0 24 24",
	stroke: "currentColor",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_11$4 = { class: "flex items-center gap-1.5 px-3 pt-3 pb-1" };
var _hoisted_12$3 = {
	key: 0,
	xmlns: "http://www.w3.org/2000/svg",
	class: "h-3 w-3 shrink-0",
	viewBox: "0 0 24 24",
	fill: "currentColor",
	style: { "color": "var(--accent-primary)" }
};
var _hoisted_13$3 = {
	class: "text-[10px] font-semibold uppercase tracking-widest",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_14$3 = ["onClick", "onKeydown"];
var _hoisted_15$3 = { class: "flex-1 min-w-0 mr-2" };
var _hoisted_16$3 = { class: "truncate text-sm" };
var _hoisted_17$3 = {
	class: "flex items-center gap-1.5 mt-0.5",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_18$3 = {
	key: 0,
	class: "text-[10px]"
};
var _hoisted_19$3 = {
	key: 1,
	class: "text-[10px]"
};
var _hoisted_20$3 = {
	key: 2,
	class: "text-[10px]"
};
var _hoisted_21$2 = {
	key: 0,
	class: "text-[10px] mt-0.5 font-medium",
	style: { "color": "var(--accent-danger)" }
};
var _hoisted_22$2 = {
	class: "relative flex items-center gap-1",
	"data-sidebar-chat-actions": ""
};
var _hoisted_23$2 = ["onClick", "aria-expanded"];
var _hoisted_24$2 = ["onClick"];
var _hoisted_25$2 = {
	key: 0,
	xmlns: "http://www.w3.org/2000/svg",
	class: "h-3.5 w-3.5 shrink-0",
	viewBox: "0 0 24 24",
	fill: "currentColor",
	style: { "color": "var(--accent-primary)" }
};
var _hoisted_26$2 = {
	key: 1,
	xmlns: "http://www.w3.org/2000/svg",
	class: "h-3.5 w-3.5 shrink-0",
	fill: "none",
	viewBox: "0 0 24 24",
	stroke: "currentColor"
};
var _hoisted_27$2 = ["onClick"];
var _hoisted_28$1 = ["onClick"];
var _hoisted_29$1 = [
	"onClick",
	"title",
	"aria-label"
];
var PINNED_KEY = "sidebar_pinned_conversations";
var _sfc_main$6 = {
	__name: "Sidebar",
	props: {
		isOpen: {
			type: Boolean,
			required: true
		},
		conversations: {
			type: Array,
			default: () => []
		},
		currentId: {
			type: String,
			default: ""
		}
	},
	emits: [
		"new-chat",
		"select-chat",
		"delete-chat",
		"export-chat"
	],
	setup(__props, { expose: __expose, emit: __emit }) {
		const props = __props;
		const emit = __emit;
		const searchQuery = ref("");
		const searchInputRef = ref(null);
		const isTouchDevice = ref(window.matchMedia("(hover: none)").matches);
		const pendingDeleteId = ref("");
		const openActionMenuId = ref("");
		let resetDeleteIntentTimer = null;
		const pinnedIds = ref(new Set(JSON.parse(localStorage.getItem(PINNED_KEY) || "[]")));
		function savePinned() {
			localStorage.setItem(PINNED_KEY, JSON.stringify([...pinnedIds.value]));
		}
		function togglePin(chatId) {
			closeActionMenu();
			const next = new Set(pinnedIds.value);
			if (next.has(chatId)) next.delete(chatId);
			else next.add(chatId);
			pinnedIds.value = next;
			savePinned();
		}
		function getDateGroup(isoString) {
			if (!isoString) return "更早";
			const date = new Date(isoString);
			if (isNaN(date.getTime())) return "更早";
			const now = /* @__PURE__ */ new Date();
			const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate());
			const yesterdayStart = new Date(todayStart);
			yesterdayStart.setDate(yesterdayStart.getDate() - 1);
			const sevenDaysAgo = new Date(todayStart);
			sevenDaysAgo.setDate(sevenDaysAgo.getDate() - 6);
			if (date >= todayStart) return "今天";
			if (date >= yesterdayStart) return "昨天";
			if (date >= sevenDaysAgo) return "本週";
			return "更早";
		}
		const DATE_GROUP_ORDER = [
			"今天",
			"昨天",
			"本週",
			"更早"
		];
		const groupedConversations = computed(() => {
			const q = searchQuery.value.trim().toLowerCase();
			const all = props.conversations;
			if (q) {
				const filtered = all.filter((c) => c.title?.toLowerCase().includes(q));
				return [{
					label: `找到 ${filtered.length} 個`,
					items: filtered,
					isSearch: true
				}];
			}
			const pinned = all.filter((c) => pinnedIds.value.has(c.id));
			const unpinned = all.filter((c) => !pinnedIds.value.has(c.id));
			const groups = [];
			if (pinned.length > 0) groups.push({
				label: "已釘選",
				items: pinned,
				isPinned: true
			});
			const dateGroupMap = /* @__PURE__ */ new Map();
			for (const c of unpinned) {
				const grp = getDateGroup(c.updatedAt);
				if (!dateGroupMap.has(grp)) dateGroupMap.set(grp, []);
				dateGroupMap.get(grp).push(c);
			}
			for (const label of DATE_GROUP_ORDER) if (dateGroupMap.has(label)) groups.push({
				label,
				items: dateGroupMap.get(label)
			});
			return groups;
		});
		const hasNoSearchResults = computed(() => {
			return searchQuery.value.trim() !== "" && groupedConversations.value.length > 0 && groupedConversations.value[0].items.length === 0;
		});
		function clearDeleteIntent() {
			if (resetDeleteIntentTimer) {
				clearTimeout(resetDeleteIntentTimer);
				resetDeleteIntentTimer = null;
			}
			pendingDeleteId.value = "";
		}
		function closeActionMenu() {
			openActionMenuId.value = "";
		}
		function toggleActionMenu(chatId) {
			openActionMenuId.value = openActionMenuId.value === chatId ? "" : chatId;
		}
		function startDeleteIntent(chatId) {
			clearDeleteIntent();
			closeActionMenu();
			pendingDeleteId.value = chatId;
			resetDeleteIntentTimer = setTimeout(() => {
				pendingDeleteId.value = "";
				resetDeleteIntentTimer = null;
			}, 2500);
		}
		function handleSelectChat(chatId) {
			clearDeleteIntent();
			closeActionMenu();
			emit("select-chat", chatId);
		}
		function handleChatKeyboardSelect(event, chatId) {
			if (event.target !== event.currentTarget) return;
			if (event.key === "Enter" || event.key === " ") {
				event.preventDefault();
				handleSelectChat(chatId);
			}
		}
		function handleDeleteClick(chatId) {
			closeActionMenu();
			if (!isTouchDevice.value) {
				emit("delete-chat", chatId);
				return;
			}
			if (pendingDeleteId.value === chatId) {
				clearDeleteIntent();
				emit("delete-chat", chatId);
				return;
			}
			startDeleteIntent(chatId);
		}
		function handleExportClick(chatId, format) {
			closeActionMenu();
			emit("export-chat", {
				chatId,
				format
			});
		}
		function handleGlobalPointerDown(event) {
			if (!(event.target instanceof Element)) {
				closeActionMenu();
				return;
			}
			if (event.target.closest("[data-sidebar-chat-actions]")) return;
			closeActionMenu();
		}
		function handleGlobalKeydown(event) {
			if (event.key === "Escape") closeActionMenu();
		}
		async function focusSearchInput() {
			await nextTick();
			if (!searchInputRef.value) return;
			searchInputRef.value.focus();
			searchInputRef.value.select();
		}
		watch(() => props.conversations.map((chat) => chat.id), (conversationIds) => {
			if (pendingDeleteId.value && !conversationIds.includes(pendingDeleteId.value)) clearDeleteIntent();
			if (openActionMenuId.value && !conversationIds.includes(openActionMenuId.value)) closeActionMenu();
			const idSet = new Set(conversationIds);
			const next = new Set([...pinnedIds.value].filter((id) => idSet.has(id)));
			if (next.size !== pinnedIds.value.size) {
				pinnedIds.value = next;
				savePinned();
			}
		});
		onMounted(() => {
			window.addEventListener("pointerdown", handleGlobalPointerDown);
			window.addEventListener("keydown", handleGlobalKeydown);
		});
		onBeforeUnmount(() => {
			clearDeleteIntent();
			window.removeEventListener("pointerdown", handleGlobalPointerDown);
			window.removeEventListener("keydown", handleGlobalKeydown);
		});
		__expose({ focusSearchInput });
		function formatRelativeTime(isoString) {
			if (!isoString) return "";
			const date = new Date(isoString);
			if (isNaN(date.getTime())) return "";
			const diffMs = Date.now() - date.getTime();
			const diffSec = Math.floor(diffMs / 1e3);
			if (diffSec < 60) return "now";
			const diffMin = Math.floor(diffSec / 60);
			if (diffMin < 60) return `${diffMin}m`;
			const diffHr = Math.floor(diffMin / 60);
			if (diffHr < 24) return `${diffHr}h`;
			const diffDay = Math.floor(diffHr / 24);
			if (diffDay < 30) return `${diffDay}d`;
			const diffMon = Math.floor(diffDay / 30);
			if (diffMon < 12) return `${diffMon}mo`;
			return `${Math.floor(diffMon / 12)}y`;
		}
		return (_ctx, _cache) => {
			return openBlock(), createElementBlock("div", {
				class: normalizeClass(["h-full border-r transition-all duration-300 ease-in-out flex flex-col shrink-0 overflow-hidden", __props.isOpen ? "w-[260px] opacity-100" : "w-0 opacity-0 border-none"]),
				style: {
					"background-color": "var(--bg-secondary)",
					"border-color": "var(--border-primary)"
				},
				inert: !__props.isOpen,
				"aria-hidden": !__props.isOpen
			}, [
				createBaseVNode("div", _hoisted_2$6, [createBaseVNode("button", {
					onClick: _cache[0] || (_cache[0] = ($event) => _ctx.$emit("new-chat")),
					class: "w-full flex items-center gap-3 px-4 py-2.5 text-sm font-medium rounded-xl transition-all text-left hover:scale-[1.02]",
					style: {
						"background-color": "var(--bg-tertiary)",
						"color": "var(--text-primary)"
					}
				}, [(openBlock(), createElementBlock("svg", _hoisted_3$6, [..._cache[3] || (_cache[3] = [createBaseVNode("path", {
					"stroke-linecap": "round",
					"stroke-linejoin": "round",
					"stroke-width": "2",
					d: "M12 4v16m8-8H4"
				}, null, -1)])])), _cache[4] || (_cache[4] = createBaseVNode("span", null, "新對話", -1))]), createBaseVNode("div", _hoisted_4$5, [(openBlock(), createElementBlock("svg", _hoisted_5$4, [..._cache[5] || (_cache[5] = [createBaseVNode("path", {
					"stroke-linecap": "round",
					"stroke-linejoin": "round",
					"stroke-width": "2",
					d: "M21 21l-4.35-4.35M17 11A6 6 0 1 1 5 11a6 6 0 0 1 12 0z"
				}, null, -1)])])), withDirectives(createBaseVNode("input", {
					ref_key: "searchInputRef",
					ref: searchInputRef,
					"onUpdate:modelValue": _cache[1] || (_cache[1] = ($event) => searchQuery.value = $event),
					type: "text",
					placeholder: "搜尋對話...",
					class: "w-full pl-8 pr-3 py-1.5 text-xs rounded-lg outline-none transition-all",
					style: {
						"background-color": "var(--bg-tertiary)",
						"color": "var(--text-primary)",
						"border": "1px solid var(--border-primary)"
					}
				}, null, 512), [[vModelText, searchQuery.value]])])]),
				createBaseVNode("div", _hoisted_6$4, [__props.conversations.length === 0 && !searchQuery.value.trim() ? (openBlock(), createElementBlock("div", _hoisted_7$4, [(openBlock(), createElementBlock("svg", _hoisted_8$4, [..._cache[6] || (_cache[6] = [createBaseVNode("path", {
					"stroke-linecap": "round",
					"stroke-linejoin": "round",
					"stroke-width": "1.5",
					d: "M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"
				}, null, -1)])])), _cache[7] || (_cache[7] = createBaseVNode("div", null, [createBaseVNode("p", {
					class: "text-xs font-medium mb-1",
					style: { "color": "var(--text-secondary)" }
				}, "尚無任何對話"), createBaseVNode("p", {
					class: "text-[11px] leading-relaxed",
					style: { "color": "var(--text-tertiary)" }
				}, [
					createTextVNode("點擊上方「新對話」按鈕"),
					createBaseVNode("br"),
					createTextVNode("開始與 AI 互動")
				])], -1))])) : hasNoSearchResults.value ? (openBlock(), createElementBlock("div", _hoisted_9$4, [(openBlock(), createElementBlock("svg", _hoisted_10$4, [..._cache[8] || (_cache[8] = [createBaseVNode("path", {
					"stroke-linecap": "round",
					"stroke-linejoin": "round",
					"stroke-width": "1.5",
					d: "M21 21l-4.35-4.35M17 11A6 6 0 1 1 5 11a6 6 0 0 1 12 0z"
				}, null, -1)])])), _cache[9] || (_cache[9] = createBaseVNode("div", null, [createBaseVNode("p", {
					class: "text-xs font-medium mb-1",
					style: { "color": "var(--text-secondary)" }
				}, "找不到符合的對話"), createBaseVNode("p", {
					class: "text-[11px] leading-relaxed",
					style: { "color": "var(--text-tertiary)" }
				}, "試試其他關鍵字")], -1))])) : (openBlock(true), createElementBlock(Fragment, { key: 2 }, renderList(groupedConversations.value, (group) => {
					return openBlock(), createElementBlock(Fragment, { key: group.label }, [createBaseVNode("div", _hoisted_11$4, [group.isPinned ? (openBlock(), createElementBlock("svg", _hoisted_12$3, [..._cache[10] || (_cache[10] = [createBaseVNode("path", { d: "M16 12V4h1V2H7v2h1v8l-2 2v2h5v6h2v-6h5v-2l-2-2z" }, null, -1)])])) : createCommentVNode("", true), createBaseVNode("span", _hoisted_13$3, toDisplayString(group.label), 1)]), (openBlock(true), createElementBlock(Fragment, null, renderList(group.items, (chat) => {
						return openBlock(), createElementBlock("div", {
							key: chat.id,
							onClick: ($event) => handleSelectChat(chat.id),
							onKeydown: ($event) => handleChatKeyboardSelect($event, chat.id),
							class: normalizeClass(["chat-item group flex items-center justify-between px-3 py-2 mb-0.5 text-sm rounded-lg cursor-pointer transition-all", {
								"chat-item--active": __props.currentId === chat.id,
								"chat-item--pinned": pinnedIds.value.has(chat.id) && !searchQuery.value.trim()
							}]),
							role: "button",
							tabindex: "0"
						}, [createBaseVNode("div", _hoisted_15$3, [
							createBaseVNode("div", _hoisted_16$3, toDisplayString(chat.title), 1),
							createBaseVNode("div", _hoisted_17$3, [
								chat.updatedAt ? (openBlock(), createElementBlock("span", _hoisted_18$3, toDisplayString(formatRelativeTime(chat.updatedAt)), 1)) : createCommentVNode("", true),
								chat.updatedAt && chat.messageCount ? (openBlock(), createElementBlock("span", _hoisted_19$3, "·")) : createCommentVNode("", true),
								chat.messageCount ? (openBlock(), createElementBlock("span", _hoisted_20$3, toDisplayString(chat.messageCount) + " 則", 1)) : createCommentVNode("", true)
							]),
							isTouchDevice.value && pendingDeleteId.value === chat.id ? (openBlock(), createElementBlock("div", _hoisted_21$2, " 再按一次刪除 ")) : createCommentVNode("", true)
						]), createBaseVNode("div", _hoisted_22$2, [
							createBaseVNode("button", {
								onClick: withModifiers(($event) => toggleActionMenu(chat.id), ["stop"]),
								class: normalizeClass([isTouchDevice.value ? "opacity-100" : "opacity-0 group-hover:opacity-100 group-focus-within:opacity-100", "sidebar-menu-button p-1 rounded transition-all"]),
								title: "對話選單",
								"aria-label": "對話選單",
								"aria-expanded": openActionMenuId.value === chat.id,
								"aria-haspopup": "menu"
							}, [..._cache[11] || (_cache[11] = [createBaseVNode("svg", {
								xmlns: "http://www.w3.org/2000/svg",
								class: "h-3.5 w-3.5",
								fill: "none",
								viewBox: "0 0 24 24",
								stroke: "currentColor"
							}, [createBaseVNode("path", {
								"stroke-linecap": "round",
								"stroke-linejoin": "round",
								"stroke-width": "2",
								d: "M6 12h.01M12 12h.01M18 12h.01"
							})], -1)])], 10, _hoisted_23$2),
							openActionMenuId.value === chat.id ? (openBlock(), createElementBlock("div", {
								key: 0,
								class: "sidebar-action-menu absolute right-0 top-7 z-30 rounded-lg shadow-lg border overflow-hidden min-w-[168px]",
								style: {
									"background-color": "var(--bg-secondary)",
									"border-color": "var(--border-primary)"
								},
								role: "menu",
								onClick: _cache[2] || (_cache[2] = withModifiers(() => {}, ["stop"]))
							}, [
								createBaseVNode("button", {
									class: "w-full text-left px-3 py-2 text-xs transition-colors flex items-center gap-2",
									style: { "color": "var(--text-secondary)" },
									onClick: withModifiers(($event) => togglePin(chat.id), ["stop"]),
									role: "menuitem"
								}, [pinnedIds.value.has(chat.id) ? (openBlock(), createElementBlock("svg", _hoisted_25$2, [..._cache[12] || (_cache[12] = [createBaseVNode("path", { d: "M16 12V4h1V2H7v2h1v8l-2 2v2h5v6h2v-6h5v-2l-2-2z" }, null, -1)])])) : (openBlock(), createElementBlock("svg", _hoisted_26$2, [..._cache[13] || (_cache[13] = [createBaseVNode("path", {
									"stroke-linecap": "round",
									"stroke-linejoin": "round",
									"stroke-width": "2",
									d: "M16 12V4h1V2H7v2h1v8l-2 2v2h5v6h2v-6h5v-2l-2-2z"
								}, null, -1)])])), createTextVNode(" " + toDisplayString(pinnedIds.value.has(chat.id) ? "取消釘選" : "釘選對話"), 1)], 8, _hoisted_24$2),
								_cache[14] || (_cache[14] = createBaseVNode("div", {
									class: "border-t",
									style: { "border-color": "var(--border-primary)" }
								}, null, -1)),
								createBaseVNode("button", {
									class: "w-full text-left px-3 py-2 text-xs transition-colors",
									style: { "color": "var(--text-secondary)" },
									onClick: withModifiers(($event) => handleExportClick(chat.id, "markdown"), ["stop"]),
									role: "menuitem"
								}, " 匯出為 Markdown ", 8, _hoisted_27$2),
								createBaseVNode("button", {
									class: "w-full text-left px-3 py-2 text-xs transition-colors",
									style: { "color": "var(--text-secondary)" },
									onClick: withModifiers(($event) => handleExportClick(chat.id, "json"), ["stop"]),
									role: "menuitem"
								}, " 匯出為 JSON ", 8, _hoisted_28$1)
							])) : createCommentVNode("", true),
							createBaseVNode("button", {
								onClick: withModifiers(($event) => handleDeleteClick(chat.id), ["stop"]),
								class: normalizeClass([
									isTouchDevice.value ? "opacity-100" : "opacity-0 group-hover:opacity-100 group-focus-within:opacity-100",
									"sidebar-delete-button p-1 rounded transition-all",
									{ "sidebar-delete-button--pending": pendingDeleteId.value === chat.id }
								]),
								title: pendingDeleteId.value === chat.id ? `再按一次刪除：${chat.title}` : `刪除對話：${chat.title}`,
								"aria-label": pendingDeleteId.value === chat.id ? `再按一次刪除：${chat.title}` : `刪除對話：${chat.title}`
							}, [..._cache[15] || (_cache[15] = [createBaseVNode("svg", {
								xmlns: "http://www.w3.org/2000/svg",
								class: "h-3.5 w-3.5",
								fill: "none",
								viewBox: "0 0 24 24",
								stroke: "currentColor"
							}, [createBaseVNode("path", {
								"stroke-linecap": "round",
								"stroke-linejoin": "round",
								"stroke-width": "2",
								d: "M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
							})], -1)])], 10, _hoisted_29$1)
						])], 42, _hoisted_14$3);
					}), 128))], 64);
				}), 128))]),
				_cache[16] || (_cache[16] = createBaseVNode("div", {
					class: "p-3 border-t",
					style: { "border-color": "var(--border-primary)" }
				}, [createBaseVNode("div", {
					class: "flex items-center gap-2 text-[10px]",
					style: { "color": "var(--text-tertiary)" }
				}, [createBaseVNode("span", null, "Server Assistant v1.0")])], -1))
			], 10, _hoisted_1$6);
		};
	}
};
var Sidebar_default = /* @__PURE__ */ __plugin_vue_export_helper_default(_sfc_main$6, [["__scopeId", "data-v-1f5cdb05"]]);

//#endregion
//#region src/components/ChatHeader.vue
var _hoisted_1$5 = {
	class: "px-5 py-4 flex justify-between items-center border-b z-50",
	style: {
		"background-color": "var(--bg-primary)",
		"border-color": "var(--border-primary)"
	}
};
var _hoisted_2$5 = { class: "flex items-center gap-2" };
var _hoisted_3$5 = { class: "flex items-center gap-2" };
var _hoisted_4$4 = {
	class: "flex items-center gap-3 px-3 py-1.5 rounded-full border",
	style: {
		"background-color": "var(--bg-secondary)",
		"border-color": "var(--border-primary)"
	}
};
var _hoisted_5$3 = { class: "flex items-center gap-1.5" };
var _hoisted_6$3 = { class: "relative flex h-2 w-2" };
var _hoisted_7$3 = {
	key: 0,
	class: "animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"
};
var _hoisted_8$3 = {
	class: "text-[10px] uppercase font-bold tracking-widest",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_9$3 = {
	class: "text-xs font-mono cursor-default",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_10$3 = {
	key: 0,
	xmlns: "http://www.w3.org/2000/svg",
	class: "h-4 w-4",
	fill: "none",
	viewBox: "0 0 24 24",
	stroke: "currentColor",
	style: { "color": "var(--accent-warning)" }
};
var _hoisted_11$3 = {
	key: 1,
	xmlns: "http://www.w3.org/2000/svg",
	class: "h-4 w-4",
	fill: "none",
	viewBox: "0 0 24 24",
	stroke: "currentColor",
	style: { "color": "var(--accent-primary)" }
};
var _sfc_main$5 = {
	__name: "ChatHeader",
	props: {
		ip: {
			type: String,
			default: "0.0.0.0"
		},
		isOnline: {
			type: Boolean,
			default: true
		}
	},
	emits: ["toggle-sidebar"],
	setup(__props) {
		const themeStore = useThemeStore();
		return (_ctx, _cache) => {
			return openBlock(), createElementBlock("header", _hoisted_1$5, [createBaseVNode("div", _hoisted_2$5, [
				createBaseVNode("button", {
					onClick: _cache[0] || (_cache[0] = ($event) => _ctx.$emit("toggle-sidebar")),
					class: "header-sidebar-toggle mr-1 p-2 rounded-lg transition-colors",
					style: { "color": "var(--text-secondary)" },
					"aria-label": "切換側邊欄"
				}, [..._cache[2] || (_cache[2] = [createBaseVNode("svg", {
					xmlns: "http://www.w3.org/2000/svg",
					class: "h-5 w-5",
					fill: "none",
					viewBox: "0 0 24 24",
					stroke: "currentColor"
				}, [createBaseVNode("path", {
					"stroke-linecap": "round",
					"stroke-linejoin": "round",
					"stroke-width": "2",
					d: "M4 6h16M4 12h16M4 18h16"
				})], -1)])]),
				_cache[3] || (_cache[3] = createBaseVNode("h1", {
					class: "text-lg font-semibold tracking-tight",
					style: { "color": "var(--text-primary)" }
				}, " CGV Lab Server Assistant ", -1)),
				renderSlot(_ctx.$slots, "model-name", {}, void 0, true)
			]), createBaseVNode("div", _hoisted_3$5, [
				createBaseVNode("div", _hoisted_4$4, [
					createBaseVNode("div", _hoisted_5$3, [createBaseVNode("span", _hoisted_6$3, [__props.isOnline ? (openBlock(), createElementBlock("span", _hoisted_7$3)) : createCommentVNode("", true), createBaseVNode("span", { class: normalizeClass(["relative inline-flex rounded-full h-2 w-2", __props.isOnline ? "bg-green-500" : "bg-red-500"]) }, null, 2)]), createBaseVNode("span", _hoisted_8$3, toDisplayString(__props.isOnline ? "Online" : "Offline"), 1)]),
					_cache[4] || (_cache[4] = createBaseVNode("div", {
						class: "w-px h-3",
						style: { "background-color": "var(--border-primary)" }
					}, null, -1)),
					createBaseVNode("div", _hoisted_9$3, toDisplayString(__props.ip), 1)
				]),
				createBaseVNode("button", {
					onClick: _cache[1] || (_cache[1] = ($event) => unref(themeStore).toggleTheme()),
					class: "p-2 rounded-lg border transition-all hover:scale-105",
					style: {
						"background-color": "var(--bg-secondary)",
						"border-color": "var(--border-primary)"
					},
					title: "切換主題",
					"aria-label": "切換主題"
				}, [unref(themeStore).isDark ? (openBlock(), createElementBlock("svg", _hoisted_10$3, [..._cache[5] || (_cache[5] = [createBaseVNode("path", {
					"stroke-linecap": "round",
					"stroke-linejoin": "round",
					"stroke-width": "2",
					d: "M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z"
				}, null, -1)])])) : (openBlock(), createElementBlock("svg", _hoisted_11$3, [..._cache[6] || (_cache[6] = [createBaseVNode("path", {
					"stroke-linecap": "round",
					"stroke-linejoin": "round",
					"stroke-width": "2",
					d: "M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z"
				}, null, -1)])]))]),
				renderSlot(_ctx.$slots, "actions", {}, void 0, true)
			])]);
		};
	}
};
var ChatHeader_default = /* @__PURE__ */ __plugin_vue_export_helper_default(_sfc_main$5, [["__scopeId", "data-v-3b29591c"]]);

//#endregion
//#region src/config/app.config.js
const UI_CONFIG = {
	theme: "dark",
	sidebarDefaultOpen: true,
	messageAnimationDuration: 300,
	scrollBehavior: "smooth",
	messageWindowSize: 80,
	messageWindowLoadChunk: 20,
	markdownMaxRenderLength: 2e4
};
const CHAT_CONFIG = {
	maxMessageLength: 8e3,
	maxMessagesPerConversation: 20,
	historyPageSize: 50,
	streamingEnabled: true,
	retryAttempts: 3,
	retryDelay: 1e3
};
var DEFAULT_COMMAND_CONFIRM_TIMEOUT_SECONDS = 120;
const COMMAND_CONFIG = { confirmTimeoutSeconds: DEFAULT_COMMAND_CONFIRM_TIMEOUT_SECONDS };
const COMMAND_CONFIRM_TIMEOUT_SECONDS = Number.isInteger(COMMAND_CONFIG.confirmTimeoutSeconds) && COMMAND_CONFIG.confirmTimeoutSeconds > 0 ? COMMAND_CONFIG.confirmTimeoutSeconds : DEFAULT_COMMAND_CONFIRM_TIMEOUT_SECONDS;

//#endregion
//#region src/components/ChatCommandRequest.vue
var _hoisted_1$4 = ["aria-label"];
var _hoisted_2$4 = { key: 0 };
var _hoisted_3$4 = { key: 1 };
var _hoisted_4$3 = { key: 2 };
var _hoisted_5$2 = { class: "cmd-summary-label" };
var _hoisted_6$2 = { class: "cmd-summary-cmd" };
var _hoisted_7$2 = ["aria-label"];
var _hoisted_8$2 = { key: 0 };
var _hoisted_9$2 = { key: 1 };
var _hoisted_10$2 = { key: 2 };
var _hoisted_11$2 = { key: 3 };
var _hoisted_12$2 = { key: 4 };
var _hoisted_13$2 = { key: 5 };
var _hoisted_14$2 = { key: 6 };
var _hoisted_15$2 = { key: 7 };
var _hoisted_16$2 = {
	key: 9,
	class: "cmd-collapse-indicator ml-auto",
	"aria-hidden": "true"
};
var _hoisted_17$2 = {
	key: 0,
	class: "mb-2 text-[11px] font-mono",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_18$2 = {
	key: 1,
	class: "mb-2 text-[11px] font-mono",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_19$2 = {
	key: 2,
	class: "mb-2 text-[11px] font-mono",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_20$2 = {
	key: 3,
	class: "mb-2 text-xs",
	style: { "color": "var(--text-secondary)" }
};
var _hoisted_21$1 = {
	key: 4,
	class: "mb-2 text-xs",
	style: { "color": "var(--text-secondary)" }
};
var _hoisted_22$1 = {
	key: 5,
	class: "mb-2 text-xs",
	style: { "color": "var(--text-secondary)" }
};
var _hoisted_23$1 = {
	class: "rounded-lg border overflow-hidden",
	style: { borderColor: "var(--border-primary)" }
};
var _hoisted_24$1 = {
	class: "flex items-center justify-between px-3 py-1",
	style: {
		"background-color": "color-mix(in srgb, var(--code-bg) 85%, var(--text-tertiary) 15%)",
		"border-bottom": "1px solid var(--border-primary)",
		"min-height": "30px"
	}
};
var _hoisted_25$1 = {
	key: 6,
	class: "flex justify-end gap-2 mt-3"
};
var _hoisted_26$1 = ["disabled"];
var _hoisted_27$1 = ["disabled"];
var COUNTDOWN_WARN_THRESHOLD_SECONDS = 60;
var COUNTDOWN_DANGER_THRESHOLD_SECONDS = 30;
var _sfc_main$4 = {
	__name: "ChatCommandRequest",
	props: {
		command: {
			type: String,
			required: true
		},
		status: {
			type: String,
			default: "pending"
		},
		disabled: {
			type: Boolean,
			default: false
		},
		createdAt: {
			type: Number,
			default: null
		},
		resolvedAt: {
			type: Number,
			default: null
		},
		expiresAt: {
			type: Number,
			default: null
		},
		ttlSeconds: {
			type: Number,
			default: COMMAND_CONFIRM_TIMEOUT_SECONDS
		}
	},
	emits: ["confirm", "cancel"],
	setup(__props, { emit: __emit }) {
		const DEFAULT_TTL_SECONDS = COMMAND_CONFIRM_TIMEOUT_SECONDS;
		const EXPIRES_AT_FORMATTER = new Intl.DateTimeFormat("zh-TW", {
			month: "2-digit",
			day: "2-digit",
			hour: "2-digit",
			minute: "2-digit",
			second: "2-digit",
			hour12: false
		});
		const props = __props;
		const emit = __emit;
		const isPending = computed(() => props.status === "pending");
		const isConfirmed = computed(() => props.status === "confirmed");
		const isCancelled = computed(() => props.status === "cancelled");
		const isExpired = computed(() => isPending.value && remainingSeconds.value <= 0);
		const isResolved = computed(() => isConfirmed.value || isCancelled.value || isExpired.value);
		const collapsed = ref(props.status === "confirmed" || props.status === "cancelled" || isInitiallyExpired(props));
		const summaryRef = ref(null);
		const resolvedAt = ref(null);
		const resolvedAtIsCreatedFallback = ref(false);
		const copied = ref(false);
		const copyFailed = ref(false);
		const effectiveTtlSeconds = computed(() => {
			if (Number.isFinite(props.ttlSeconds) && props.ttlSeconds > 0) return Math.floor(props.ttlSeconds);
			return DEFAULT_TTL_SECONDS;
		});
		const deadlineMs = computed(() => {
			if (Number.isFinite(props.expiresAt)) return props.expiresAt;
			if (Number.isFinite(props.createdAt)) return props.createdAt + effectiveTtlSeconds.value * 1e3;
			return null;
		});
		const hasDeadline = computed(() => Number.isFinite(deadlineMs.value));
		const remainingSeconds = ref(isInitiallyExpired(props) ? 0 : effectiveTtlSeconds.value);
		let countdownInterval = null;
		let collapseTimer = null;
		function isInitiallyExpired(currentProps) {
			if (currentProps.status !== "pending") return false;
			if (Number.isFinite(currentProps.expiresAt)) return currentProps.expiresAt <= Date.now();
			if (Number.isFinite(currentProps.createdAt)) {
				const ttl = Number.isFinite(currentProps.ttlSeconds) && currentProps.ttlSeconds > 0 ? Math.floor(currentProps.ttlSeconds) : DEFAULT_TTL_SECONDS;
				return currentProps.createdAt + ttl * 1e3 <= Date.now();
			}
			return false;
		}
		function stopCountdown() {
			if (!countdownInterval) return;
			clearInterval(countdownInterval);
			countdownInterval = null;
		}
		function scheduleCollapse(delayMs = 0) {
			if (collapseTimer) {
				clearTimeout(collapseTimer);
				collapseTimer = null;
			}
			collapseTimer = setTimeout(() => {
				collapsed.value = true;
				collapseTimer = null;
			}, delayMs);
		}
		function updateCountdown() {
			if (!hasDeadline.value) {
				remainingSeconds.value = effectiveTtlSeconds.value;
				return;
			}
			const remainingMs = deadlineMs.value - Date.now();
			remainingSeconds.value = Math.max(0, Math.ceil(remainingMs / 1e3));
			if (remainingSeconds.value <= 0) stopCountdown();
		}
		function startCountdown() {
			stopCountdown();
			if (props.status !== "pending") return;
			updateCountdown();
			if (hasDeadline.value && remainingSeconds.value > 0) countdownInterval = setInterval(updateCountdown, 1e3);
		}
		onUnmounted(() => {
			stopCountdown();
			if (collapseTimer) {
				clearTimeout(collapseTimer);
				collapseTimer = null;
			}
		});
		watch(() => [
			props.status,
			props.createdAt,
			props.expiresAt,
			props.ttlSeconds
		], ([status]) => {
			if (status === "pending") {
				startCountdown();
				return;
			}
			stopCountdown();
		}, { immediate: true });
		const RESOLVED_AT_FORMATTER = new Intl.DateTimeFormat("zh-TW", {
			month: "2-digit",
			day: "2-digit",
			hour: "2-digit",
			minute: "2-digit",
			second: "2-digit",
			hour12: false
		});
		watch(() => [
			props.status,
			props.resolvedAt,
			props.createdAt
		], (next, previous) => {
			const [newStatus, newResolvedAt, newCreatedAt] = next;
			const oldStatus = Array.isArray(previous) ? previous[0] : null;
			if (newStatus !== "pending") stopCountdown();
			if (newStatus === "confirmed" || newStatus === "cancelled") {
				if (Number.isFinite(newResolvedAt)) {
					resolvedAt.value = RESOLVED_AT_FORMATTER.format(new Date(newResolvedAt));
					resolvedAtIsCreatedFallback.value = false;
				} else if (Number.isFinite(newCreatedAt)) {
					resolvedAt.value = RESOLVED_AT_FORMATTER.format(new Date(newCreatedAt));
					resolvedAtIsCreatedFallback.value = true;
				} else if (!resolvedAt.value) {
					resolvedAt.value = RESOLVED_AT_FORMATTER.format(/* @__PURE__ */ new Date());
					resolvedAtIsCreatedFallback.value = false;
				}
				if (oldStatus === "pending") scheduleCollapse(1800);
				return;
			}
			if (newStatus === "pending" && !isExpired.value) resolvedAt.value = null;
			resolvedAtIsCreatedFallback.value = false;
		}, { immediate: true });
		watch(isExpired, (expired) => {
			if (!expired) return;
			resolvedAt.value = RESOLVED_AT_FORMATTER.format(new Date(hasDeadline.value ? deadlineMs.value : Date.now()));
			resolvedAtIsCreatedFallback.value = false;
			scheduleCollapse(0);
		}, { immediate: true });
		const resolvedTimestampLabel = computed(() => {
			if (isExpired.value) return "逾時時間";
			if (resolvedAtIsCreatedFallback.value) return "建立時間";
			return isConfirmed.value ? "執行時間" : "取消時間";
		});
		const createdAtLabel = computed(() => {
			if (!Number.isFinite(props.createdAt)) return "";
			return RESOLVED_AT_FORMATTER.format(new Date(props.createdAt));
		});
		const showCountdown = computed(() => isPending.value && hasDeadline.value && remainingSeconds.value <= COUNTDOWN_WARN_THRESHOLD_SECONDS);
		const countdownDanger = computed(() => remainingSeconds.value <= COUNTDOWN_DANGER_THRESHOLD_SECONDS);
		const expiresAtLabel = computed(() => {
			if (!hasDeadline.value) return "";
			return EXPIRES_AT_FORMATTER.format(new Date(deadlineMs.value));
		});
		const remainingLabel = computed(() => formatRemaining(remainingSeconds.value));
		function formatRemaining(s) {
			if (s <= 0) return "已逾時";
			if (s >= 60) return `${Math.floor(s / 60)}分${String(s % 60).padStart(2, "0")}秒`;
			return `${s} 秒`;
		}
		const RISK_REASONS = {
			rm: "永久刪除檔案，無法復原",
			rmdir: "永久刪除目錄，無法復原",
			mv: "移動或覆蓋現有檔案",
			cp: "複製並可能覆蓋大量資料",
			rsync: "同步並可能覆蓋目標資料",
			tar: "封存或解壓縮大量資料",
			zip: "封存大量資料",
			unzip: "解壓縮並可能覆蓋現有檔案",
			chmod: "變更檔案存取權限",
			chown: "變更檔案擁有者",
			useradd: "新增系統使用者",
			userdel: "刪除系統使用者",
			usermod: "修改系統使用者帳號",
			groupadd: "新增系統群組",
			groupdel: "刪除系統群組",
			passwd: "變更使用者密碼",
			mount: "掛載磁碟裝置",
			umount: "卸載磁碟裝置",
			crontab: "修改排程任務",
			apt: "安裝或移除系統套件",
			yum: "安裝或移除系統套件",
			dnf: "安裝或移除系統套件"
		};
		const riskReason = computed(() => {
			if (!props.command) return null;
			const words = props.command.trim().split(/\s+/);
			const first = words[0].toLowerCase();
			return RISK_REASONS[first === "sudo" ? (words[1] || "").toLowerCase() : first] || null;
		});
		async function copyCommand() {
			try {
				if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(props.command);
				else {
					const ta = document.createElement("textarea");
					ta.value = props.command;
					ta.style.cssText = "position:fixed;opacity:0;pointer-events:none;left:-9999px";
					document.body.appendChild(ta);
					ta.select();
					document.execCommand("copy");
					document.body.removeChild(ta);
				}
				copied.value = true;
				setTimeout(() => {
					copied.value = false;
				}, 2e3);
			} catch {
				copyFailed.value = true;
				setTimeout(() => {
					copyFailed.value = false;
				}, 2e3);
			}
		}
		function collapseDetails() {
			if (!isResolved.value) return;
			collapsed.value = true;
			nextTick(() => summaryRef.value?.focus());
		}
		return (_ctx, _cache) => {
			return openBlock(), createElementBlock(Fragment, null, [createVNode(Transition, { name: "cmd-collapse" }, {
				default: withCtx(() => [collapsed.value ? (openBlock(), createElementBlock("button", {
					key: 0,
					ref_key: "summaryRef",
					ref: summaryRef,
					type: "button",
					onClick: _cache[0] || (_cache[0] = ($event) => collapsed.value = false),
					class: "cmd-summary-row",
					style: normalizeStyle({ borderColor: isConfirmed.value ? "var(--accent-success)" : isExpired.value ? "var(--accent-danger)" : "var(--border-secondary)" }),
					"aria-label": isConfirmed.value ? "指令已執行，點擊展開詳情" : isExpired.value ? "確認已逾時，點擊展開詳情" : "操作已取消，點擊展開詳情"
				}, [
					createBaseVNode("span", {
						class: "cmd-summary-status",
						style: normalizeStyle({ color: isConfirmed.value ? "var(--accent-success)" : isExpired.value ? "var(--accent-danger)" : "var(--text-tertiary)" })
					}, [isConfirmed.value ? (openBlock(), createElementBlock("span", _hoisted_2$4, "✅")) : isExpired.value ? (openBlock(), createElementBlock("span", _hoisted_3$4, "⏰")) : (openBlock(), createElementBlock("span", _hoisted_4$3, "🚫")), createBaseVNode("span", _hoisted_5$2, toDisplayString(isConfirmed.value ? "已執行" : isExpired.value ? "已逾時" : "已取消"), 1)], 4),
					createBaseVNode("code", _hoisted_6$2, toDisplayString(__props.command), 1),
					_cache[3] || (_cache[3] = createBaseVNode("svg", {
						class: "cmd-summary-chevron",
						xmlns: "http://www.w3.org/2000/svg",
						width: "12",
						height: "12",
						viewBox: "0 0 24 24",
						fill: "none",
						stroke: "currentColor",
						"stroke-width": "2.5",
						"stroke-linecap": "round",
						"stroke-linejoin": "round"
					}, [createBaseVNode("polyline", { points: "6 9 12 15 18 9" })], -1))
				], 12, _hoisted_1$4)) : createCommentVNode("", true)]),
				_: 1
			}), createVNode(Transition, { name: "cmd-card" }, {
				default: withCtx(() => [!collapsed.value ? (openBlock(), createElementBlock("div", {
					key: 0,
					role: "group",
					"aria-label": isExpired.value ? "確認已逾時" : isPending.value ? "高風險指令確認" : isConfirmed.value ? "指令已確認執行" : "操作已取消",
					class: "rounded-xl p-3 border transition-all",
					style: normalizeStyle({
						backgroundColor: "var(--bg-input)",
						borderColor: isExpired.value ? "var(--accent-danger)" : isPending.value ? "var(--accent-warning)" : isConfirmed.value ? "var(--accent-success)" : "var(--border-secondary)"
					})
				}, [
					(openBlock(), createBlock(resolveDynamicComponent(isResolved.value ? "button" : "div"), {
						class: normalizeClass(["cmd-card-header flex w-full items-center gap-2 font-semibold text-sm mb-2", { "cmd-card-header-collapsible": isResolved.value }]),
						type: isResolved.value ? "button" : void 0,
						"aria-label": isResolved.value ? "收合詳情" : void 0,
						role: isResolved.value ? void 0 : "status",
						"aria-live": isResolved.value ? void 0 : "polite",
						style: normalizeStyle({ color: isExpired.value ? "var(--accent-danger)" : isPending.value ? "var(--accent-warning)" : isConfirmed.value ? "var(--accent-success)" : "var(--text-tertiary)" }),
						onClick: collapseDetails
					}, {
						default: withCtx(() => [
							isExpired.value ? (openBlock(), createElementBlock("span", _hoisted_8$2, "⏰")) : isPending.value ? (openBlock(), createElementBlock("span", _hoisted_9$2, "⚠️")) : isConfirmed.value ? (openBlock(), createElementBlock("span", _hoisted_10$2, "✅")) : (openBlock(), createElementBlock("span", _hoisted_11$2, "🚫")),
							isExpired.value ? (openBlock(), createElementBlock("span", _hoisted_12$2, "確認已逾時")) : isPending.value ? (openBlock(), createElementBlock("span", _hoisted_13$2, "系統變更確認")) : isConfirmed.value ? (openBlock(), createElementBlock("span", _hoisted_14$2, "指令已確認執行")) : (openBlock(), createElementBlock("span", _hoisted_15$2, "操作已取消")),
							showCountdown.value && !isExpired.value ? (openBlock(), createElementBlock("span", {
								key: 8,
								class: normalizeClass(["ml-auto text-[11px] font-mono px-2 py-0.5 rounded-full countdown-pill", countdownDanger.value ? "countdown-danger" : "countdown-warn"]),
								"aria-live": "polite"
							}, toDisplayString(formatRemaining(remainingSeconds.value)), 3)) : createCommentVNode("", true),
							isResolved.value ? (openBlock(), createElementBlock("span", _hoisted_16$2, [..._cache[4] || (_cache[4] = [createBaseVNode("svg", {
								xmlns: "http://www.w3.org/2000/svg",
								width: "12",
								height: "12",
								viewBox: "0 0 24 24",
								fill: "none",
								stroke: "currentColor",
								"stroke-width": "2.5",
								"stroke-linecap": "round",
								"stroke-linejoin": "round"
							}, [createBaseVNode("polyline", { points: "18 15 12 9 6 15" })], -1)])])) : createCommentVNode("", true)
						]),
						_: 1
					}, 8, [
						"class",
						"type",
						"aria-label",
						"role",
						"aria-live",
						"style"
					])),
					isPending.value && hasDeadline.value ? (openBlock(), createElementBlock("div", _hoisted_17$2, " 剩餘時間：" + toDisplayString(remainingLabel.value) + " ｜ 到期時間：" + toDisplayString(expiresAtLabel.value), 1)) : createCommentVNode("", true),
					(isConfirmed.value || isCancelled.value || isExpired.value) && resolvedAt.value ? (openBlock(), createElementBlock("div", _hoisted_18$2, toDisplayString(resolvedTimestampLabel.value) + "：" + toDisplayString(resolvedAt.value), 1)) : createCommentVNode("", true),
					createdAtLabel.value && !resolvedAtIsCreatedFallback.value ? (openBlock(), createElementBlock("div", _hoisted_19$2, " 建立時間：" + toDisplayString(createdAtLabel.value), 1)) : createCommentVNode("", true),
					isPending.value && !isExpired.value ? (openBlock(), createElementBlock("div", _hoisted_20$2, " AI 請求執行以下高風險指令： ")) : createCommentVNode("", true),
					riskReason.value ? (openBlock(), createElementBlock("div", _hoisted_21$1, " 高風險說明：" + toDisplayString(riskReason.value), 1)) : createCommentVNode("", true),
					isExpired.value ? (openBlock(), createElementBlock("div", _hoisted_22$1, " 確認視窗已過期，請重新發送指令。 ")) : createCommentVNode("", true),
					createBaseVNode("div", _hoisted_23$1, [createBaseVNode("div", _hoisted_24$1, [_cache[6] || (_cache[6] = createBaseVNode("span", {
						class: "text-[10px] font-semibold uppercase tracking-wide font-mono",
						style: { "color": "var(--text-tertiary)" }
					}, "shell", -1)), createBaseVNode("button", {
						type: "button",
						onClick: withModifiers(copyCommand, ["stop"]),
						class: normalizeClass(["cmd-copy-btn", {
							"cmd-copy-btn-copied": copied.value,
							"cmd-copy-btn-error": copyFailed.value
						}]),
						title: "複製指令"
					}, [..._cache[5] || (_cache[5] = [createBaseVNode("svg", {
						xmlns: "http://www.w3.org/2000/svg",
						width: "13",
						height: "13",
						viewBox: "0 0 24 24",
						fill: "none",
						stroke: "currentColor",
						"stroke-width": "2",
						"stroke-linecap": "round",
						"stroke-linejoin": "round"
					}, [createBaseVNode("rect", {
						x: "9",
						y: "9",
						width: "13",
						height: "13",
						rx: "2",
						ry: "2"
					}), createBaseVNode("path", { d: "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" })], -1)])], 2)]), createBaseVNode("pre", {
						class: "p-3 overflow-x-auto text-sm font-mono m-0 border-0 rounded-none",
						style: normalizeStyle({
							backgroundColor: "var(--code-bg)",
							color: isPending.value ? "var(--accent-warning)" : isConfirmed.value ? "var(--accent-success)" : "var(--text-tertiary)"
						})
					}, [createBaseVNode("code", null, toDisplayString(__props.command), 1)], 4)]),
					isPending.value && !isExpired.value ? (openBlock(), createElementBlock("div", _hoisted_25$1, [createBaseVNode("button", {
						onClick: _cache[1] || (_cache[1] = ($event) => emit("cancel")),
						disabled: __props.disabled,
						class: "px-3 py-1.5 rounded-lg text-xs font-medium border transition-all",
						style: normalizeStyle({
							backgroundColor: "transparent",
							borderColor: "var(--border-secondary)",
							color: "var(--text-secondary)",
							opacity: __props.disabled ? .5 : 1,
							cursor: __props.disabled ? "not-allowed" : "pointer"
						})
					}, " 取消 ", 12, _hoisted_26$1), createBaseVNode("button", {
						onClick: _cache[2] || (_cache[2] = ($event) => emit("confirm", __props.command)),
						disabled: __props.disabled,
						class: "px-3 py-1.5 rounded-lg text-xs font-semibold transition-all",
						style: normalizeStyle({
							backgroundColor: "var(--accent-warning)",
							color: "#000",
							opacity: __props.disabled ? .5 : 1,
							cursor: __props.disabled ? "not-allowed" : "pointer"
						})
					}, toDisplayString(__props.disabled ? "處理中..." : "確認執行"), 13, _hoisted_27$1)])) : createCommentVNode("", true)
				], 12, _hoisted_7$2)) : createCommentVNode("", true)]),
				_: 1
			})], 64);
		};
	}
};
var ChatCommandRequest_default = /* @__PURE__ */ __plugin_vue_export_helper_default(_sfc_main$4, [["__scopeId", "data-v-99edf62c"]]);

//#endregion
//#region src/components/ControlPanel.vue
var _hoisted_1$3 = {
	class: "flex items-center justify-between px-4 py-2 border-b",
	style: { "border-color": "var(--border-primary)" }
};
var _hoisted_2$3 = { class: "flex items-center gap-4" };
var _hoisted_3$3 = { class: "flex flex-col gap-1" };
var _hoisted_4$2 = ["label"];
var _hoisted_5$1 = ["value"];
var _hoisted_6$1 = {
	key: 0,
	class: "text-[11px] font-mono flex items-center gap-2",
	style: { "color": "var(--accent-danger, #ef4444)" }
};
var _hoisted_7$1 = { class: "flex items-end gap-2 px-4 py-3" };
var _hoisted_8$1 = {
	key: 0,
	class: "absolute left-2 right-2 bottom-full mb-2 rounded-xl border shadow-lg overflow-hidden z-50",
	style: {
		"background-color": "var(--bg-input)",
		"border-color": "var(--border-primary)"
	}
};
var _hoisted_9$1 = [
	"onMouseenter",
	"onFocus",
	"onPointerdown"
];
var _hoisted_10$1 = { class: "flex items-center gap-3 min-w-0" };
var _hoisted_11$1 = {
	class: "text-xs font-mono px-2 py-1 rounded-lg",
	style: {
		"background-color": "color-mix(in srgb, var(--accent-primary) 12%, transparent)",
		"color": "var(--text-primary)"
	}
};
var _hoisted_12$1 = {
	class: "text-xs truncate",
	style: { "color": "var(--text-secondary)" }
};
var _hoisted_13$1 = {
	key: 0,
	class: "text-[10px] font-mono truncate",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_14$1 = [
	"value",
	"onKeydown",
	"maxlength",
	"placeholder"
];
var _hoisted_15$1 = {
	key: 1,
	class: "px-2 pt-1 text-[11px] font-mono whitespace-normal break-words",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_16$1 = { class: "flex-shrink-0 mb-1 flex items-center gap-1" };
var _hoisted_17$1 = ["title", "aria-label"];
var _hoisted_18$1 = {
	key: 0,
	class: "px-5 pb-2 text-xs font-mono flex items-center gap-2",
	style: { "color": "var(--accent-danger, #ef4444)" }
};
var _hoisted_19$1 = {
	key: 1,
	class: "px-5 pb-2 text-xs font-mono flex items-center gap-2",
	style: { "color": "var(--accent-secondary)" }
};
var _hoisted_20$1 = { class: "animate-pulse" };
var _sfc_main$3 = {
	__name: "ControlPanel",
	props: /* @__PURE__ */ mergeModels([
		"isProcessing",
		"userInput",
		"statusMessage",
		"availableModels",
		"isAdmin",
		"isOnline"
	], {
		"model": {},
		"modelModifiers": {}
	}),
	emits: /* @__PURE__ */ mergeModels([
		"update:model",
		"update:userInput",
		"send",
		"stop",
		"composer-resize"
	], ["update:model"]),
	setup(__props, { emit: __emit }) {
		const props = __props;
		const emit = __emit;
		const model = useModel(__props, "model");
		const maxMessageLength = CHAT_CONFIG.maxMessageLength || 8e3;
		const SLASH_COMMANDS = [
			{
				cmd: "/addSSHKey",
				desc: "為既有使用者加入 SSH key",
				hint: "/addSSHKey [username]"
			},
			{
				cmd: "/addUser",
				desc: "新增使用者",
				hint: "/addUser [username]"
			},
			{
				cmd: "/docker",
				desc: "Docker 狀態",
				hint: "/docker"
			},
			{
				cmd: "/gpu",
				desc: "顯示 GPU 狀態"
			},
			{
				cmd: "/help",
				desc: "列出可用指令與網站功能"
			},
			{
				cmd: "/mount",
				desc: "格式化並掛載整顆磁碟 (Admin)",
				hint: "/mount <device> <target> [fstype] [options]"
			},
			{
				cmd: "/offload",
				desc: "搬移 home 資料夾到其他硬碟並建立 symlink",
				hint: "/offload"
			},
			{
				cmd: "/port",
				desc: "列出監聽中的 Port"
			},
			{
				cmd: "/status",
				desc: "顯示系統狀態"
			},
			{
				cmd: "/top",
				desc: "顯示高耗能進程",
				hint: "/top cpu|mem [limit]"
			},
			{
				cmd: "/users",
				desc: "列出系統可登入使用者"
			}
		];
		const ADMIN_ONLY_SLASH_COMMANDS = new Set([
			"/addSSHKey",
			"/addUser",
			"/mount",
			"/offload",
			"/users"
		]);
		const SLASH_COMMAND_ALIASES = {
			"/add_user": "/addUser",
			"/createuser": "/addUser",
			"/create_user": "/addUser",
			"/add_ssh_key": "/addSSHKey",
			"/addssh": "/addSSHKey",
			"/sshkey": "/addSSHKey",
			"/gpustatus": "/gpu",
			"/gpu_status": "/gpu",
			"/ports": "/port",
			"/systemstatus": "/status",
			"/system_status": "/status",
			"/?": "/help"
		};
		const SLASH_USAGE_HINTS = {
			"/addSSHKey": "/addSSHKey [username]",
			"/addUser": "/addUser [username]",
			"/docker": "/docker",
			"/gpu": "/gpu",
			"/help": "/help",
			"/mount": "/mount <device> <target> [fstype] [options]",
			"/offload": "/offload <source_abs_path> <target_disk_root_abs_path>",
			"/port": "/port",
			"/status": "/status",
			"/top": "/top cpu|mem [limit 1-30]",
			"/users": "/users"
		};
		const modelGroups = computed(() => {
			const groups = {};
			for (const [key, config] of Object.entries(props.availableModels || {})) {
				const cat = config.category || "Other";
				if (!groups[cat]) groups[cat] = [];
				const baseLabel = config.label || key;
				const unavailable = config.available === false;
				groups[cat].push({
					value: key,
					label: unavailable ? `${baseLabel} (⚠️ 目前負載高)` : baseLabel
				});
			}
			return Object.keys(groups).map((label) => ({
				label,
				options: groups[label]
			}));
		});
		const selectedModelConfig = computed(() => {
			return (props.availableModels || {})[model.value] || null;
		});
		const suggestedAlternativeKey = computed(() => {
			const cfg = selectedModelConfig.value;
			if (!cfg || cfg.available !== false) return "";
			const alt = typeof cfg.suggestAlternative === "string" ? cfg.suggestAlternative.trim() : "";
			if (!alt) return "";
			const altConfig = (props.availableModels || {})[alt];
			if (!altConfig || altConfig.available === false) return "";
			return alt;
		});
		const suggestedAlternativeLabel = computed(() => {
			const key = suggestedAlternativeKey.value;
			if (!key) return "";
			return (props.availableModels || {})[key]?.label || key;
		});
		const switchToSuggestedAlternative = () => {
			if (!suggestedAlternativeKey.value) return;
			model.value = suggestedAlternativeKey.value;
		};
		const textareaRef = ref(null);
		const isComposing = ref(false);
		const isListening = ref(false);
		const isFocused = ref(false);
		let recognition = null;
		const activeSlashIndex = ref(0);
		const slashAreaRef = ref(null);
		const slashMenuDismissed = ref(false);
		const slashState = computed(() => {
			const t = (props.userInput || "").trimStart();
			if (!t.startsWith("/")) return null;
			const afterSlashTrimLeft = t.slice(1).replace(/^\s+/, "");
			if (!afterSlashTrimLeft) return {
				query: "",
				hasArgs: false
			};
			const m = afterSlashTrimLeft.match(/^(\S+)(\s+.*)?$/);
			return {
				query: m && m[1] ? m[1] : "",
				hasArgs: !!(m && m[2])
			};
		});
		const slashItems = computed(() => {
			if (!slashState.value) return [];
			const q = (slashState.value.query || "").toLowerCase();
			const allowed = props.isAdmin ? SLASH_COMMANDS : SLASH_COMMANDS.filter((x) => !ADMIN_ONLY_SLASH_COMMANDS.has(x.cmd));
			if (!q) return allowed;
			const needle = "/" + q;
			return allowed.filter((x) => x.cmd.toLowerCase().startsWith(needle));
		});
		const activeSlashUsageHint = computed(() => {
			if (!slashState.value) return "";
			const q = (slashState.value.query || "").trim().toLowerCase();
			if (!q) return "";
			const allowed = props.isAdmin ? SLASH_COMMANDS : SLASH_COMMANDS.filter((x) => !ADMIN_ONLY_SLASH_COMMANDS.has(x.cmd));
			const typed = "/" + q;
			const direct = allowed.find((x) => x.cmd.toLowerCase() === typed);
			if (direct) return SLASH_USAGE_HINTS[direct.cmd] || direct.hint || "";
			const canonical = SLASH_COMMAND_ALIASES[typed];
			if (!canonical) return "";
			if (!props.isAdmin && ADMIN_ONLY_SLASH_COMMANDS.has(canonical)) return "";
			return SLASH_USAGE_HINTS[canonical] || "";
		});
		const showSlashMenu = computed(() => {
			if (props.isProcessing) return false;
			return !!slashState.value && !slashState.value.hasArgs && slashItems.value.length > 0;
		});
		watch(() => slashState.value?.query, () => {
			activeSlashIndex.value = 0;
			slashMenuDismissed.value = false;
		});
		const isSlashMenuOpen = computed(() => showSlashMenu.value && !slashMenuDismissed.value);
		const onGlobalPointerDown = (e) => {
			if (!isSlashMenuOpen.value) return;
			const area = slashAreaRef.value;
			if (!area) return;
			const t = e?.target;
			if (t && area.contains(t)) return;
			slashMenuDismissed.value = true;
		};
		onMounted(() => {
			document.addEventListener("pointerdown", onGlobalPointerDown, true);
			nextTick(adjustHeight);
		});
		onBeforeUnmount(() => {
			document.removeEventListener("pointerdown", onGlobalPointerDown, true);
			recognition?.stop();
		});
		const onCompositionStart = () => {
			isComposing.value = true;
		};
		const onCompositionEnd = () => {
			setTimeout(() => {
				isComposing.value = false;
			}, 0);
		};
		const adjustHeight = () => {
			const el = textareaRef.value;
			if (!el) return;
			el.style.height = "auto";
			el.style.height = el.scrollHeight + "px";
			el.style.overflowY = el.scrollHeight > el.clientHeight ? "auto" : "hidden";
			emit("composer-resize", el.offsetHeight);
		};
		watch(() => props.userInput, () => {
			nextTick(adjustHeight);
		});
		const onInput = (e) => {
			emit("update:userInput", e.target.value);
			adjustHeight();
		};
		const setInput = (v) => {
			emit("update:userInput", v);
			nextTick(() => {
				adjustHeight();
				textareaRef.value?.focus?.();
			});
		};
		const applySlashCommand = (cmd) => {
			const v = props.userInput || "";
			let next = v.replace(/^(\s*)\/\s*\S*/, `$1${cmd}`);
			if (next === v) {
				const firstWs = v.search(/\s/);
				next = firstWs === -1 ? cmd : cmd + v.slice(firstWs);
			}
			if (next.trimStart() === cmd) next = next + " ";
			setInput(next);
		};
		const onArrowDown = (e) => {
			if (!isSlashMenuOpen.value) return;
			e.preventDefault();
			activeSlashIndex.value = Math.min(activeSlashIndex.value + 1, slashItems.value.length - 1);
		};
		const onArrowUp = (e) => {
			if (!isSlashMenuOpen.value) return;
			e.preventDefault();
			activeSlashIndex.value = Math.max(activeSlashIndex.value - 1, 0);
		};
		const onEscape = () => {
			if (!isSlashMenuOpen.value) return;
			slashMenuDismissed.value = true;
			activeSlashIndex.value = 0;
		};
		const setActiveSlashIndex = (idx) => {
			if (!isSlashMenuOpen.value) return;
			if (typeof idx !== "number") return;
			activeSlashIndex.value = Math.max(0, Math.min(idx, slashItems.value.length - 1));
		};
		const openSlashMenu = () => {
			if (props.isProcessing) return;
			slashMenuDismissed.value = false;
			const current = props.userInput || "";
			if (current.length === 0) {
				setInput("/");
				return;
			}
			const el = textareaRef.value;
			const pos = el ? el.selectionStart ?? current.length : current.length;
			emit("update:userInput", current.slice(0, pos) + "/" + current.slice(pos));
			nextTick(() => {
				adjustHeight();
				if (el) {
					el.focus();
					el.setSelectionRange(pos + 1, pos + 1);
				}
			});
		};
		const onEnterPress = (e) => {
			if (e.isComposing || isComposing.value) return;
			const v = props.userInput || "";
			const t = v.trimStart();
			if (isSlashMenuOpen.value && t.startsWith("/")) {
				const item = slashItems.value[activeSlashIndex.value] || slashItems.value[0];
				if (item) {
					applySlashCommand(item.cmd);
					return;
				}
			}
			if (!props.isProcessing && props.isOnline !== false && v.trim()) emit("send");
		};
		const toggleVoice = () => {
			if (isListening.value) {
				recognition?.stop();
				isListening.value = false;
				return;
			}
			const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
			if (!SpeechRecognition) {
				alert("您的瀏覽器不支援語音輸入 (Web Speech API)");
				return;
			}
			recognition = new SpeechRecognition();
			recognition.lang = "zh-TW";
			recognition.interimResults = true;
			recognition.continuous = false;
			const initialText = props.userInput || "";
			recognition.onstart = () => {
				isListening.value = true;
			};
			recognition.onresult = (event) => {
				let transcript = "";
				for (let i = 0; i < event.results.length; ++i) transcript += event.results[i][0].transcript;
				emit("update:userInput", (initialText ? initialText + " " : "") + transcript);
			};
			recognition.onend = () => {
				isListening.value = false;
			};
			recognition.onerror = (e) => {
				console.error("Speech recognition error", e);
				isListening.value = false;
			};
			recognition.start();
		};
		return (_ctx, _cache) => {
			return openBlock(), createElementBlock("div", {
				class: "max-w-4xl mx-auto rounded-2xl border overflow-visible shadow-lg transition-all",
				style: normalizeStyle({
					backgroundColor: "var(--bg-secondary)",
					borderColor: isFocused.value ? "var(--border-secondary)" : "var(--border-primary)",
					boxShadow: isFocused.value ? "0 0 0 1px var(--border-secondary)" : "none"
				}),
				onFocusin: _cache[3] || (_cache[3] = ($event) => isFocused.value = true),
				onFocusout: _cache[4] || (_cache[4] = ($event) => isFocused.value = false)
			}, [
				createBaseVNode("div", _hoisted_1$3, [createBaseVNode("div", _hoisted_2$3, [createBaseVNode("div", _hoisted_3$3, [withDirectives(createBaseVNode("select", {
					"onUpdate:modelValue": _cache[0] || (_cache[0] = ($event) => model.value = $event),
					class: "text-xs uppercase border rounded-lg px-2 py-1 cursor-pointer outline-none",
					style: {
						"background-color": "var(--bg-input)",
						"color": "var(--text-secondary)",
						"border-color": "var(--border-primary)"
					}
				}, [(openBlock(true), createElementBlock(Fragment, null, renderList(modelGroups.value, (group) => {
					return openBlock(), createElementBlock("optgroup", {
						key: group.label,
						label: group.label
					}, [(openBlock(true), createElementBlock(Fragment, null, renderList(group.options, (opt) => {
						return openBlock(), createElementBlock("option", {
							key: opt.value,
							value: opt.value,
							class: "py-1"
						}, toDisplayString(opt.label), 9, _hoisted_5$1);
					}), 128))], 8, _hoisted_4$2);
				}), 128))], 512), [[vModelSelect, model.value]]), selectedModelConfig.value && selectedModelConfig.value.available === false ? (openBlock(), createElementBlock("div", _hoisted_6$1, [_cache[5] || (_cache[5] = createBaseVNode("span", null, "⚠️ 目前負載高", -1)), suggestedAlternativeKey.value ? (openBlock(), createElementBlock("button", {
					key: 0,
					type: "button",
					class: "underline underline-offset-2",
					style: { "color": "var(--accent-primary)" },
					onClick: switchToSuggestedAlternative
				}, " 建議切換至 " + toDisplayString(suggestedAlternativeLabel.value), 1)) : createCommentVNode("", true)])) : createCommentVNode("", true)])])]),
				createBaseVNode("div", _hoisted_7$1, [createBaseVNode("div", {
					ref_key: "slashAreaRef",
					ref: slashAreaRef,
					class: "flex-1 relative min-w-0"
				}, [
					isSlashMenuOpen.value ? (openBlock(), createElementBlock("div", _hoisted_8$1, [(openBlock(true), createElementBlock(Fragment, null, renderList(slashItems.value, (item, idx) => {
						return openBlock(), createElementBlock("button", {
							key: item.cmd,
							type: "button",
							class: "w-full text-left px-3 py-2 flex items-center justify-between gap-3 border-b last:border-b-0",
							style: normalizeStyle([idx === activeSlashIndex.value ? "background-color: color-mix(in srgb, var(--accent-primary) 10%, transparent);" : "", { "border-color": "color-mix(in srgb, var(--border-primary) 70%, transparent)" }]),
							onMouseenter: ($event) => setActiveSlashIndex(idx),
							onFocus: ($event) => setActiveSlashIndex(idx),
							onPointerdown: withModifiers(($event) => applySlashCommand(item.cmd), ["prevent"])
						}, [createBaseVNode("div", _hoisted_10$1, [createBaseVNode("code", _hoisted_11$1, toDisplayString(item.cmd), 1), createBaseVNode("span", _hoisted_12$1, toDisplayString(item.desc), 1)]), item.hint ? (openBlock(), createElementBlock("span", _hoisted_13$1, toDisplayString(item.hint), 1)) : createCommentVNode("", true)], 44, _hoisted_9$1);
					}), 128))])) : createCommentVNode("", true),
					createBaseVNode("textarea", {
						ref_key: "textareaRef",
						ref: textareaRef,
						value: __props.userInput,
						onInput,
						onCompositionstart: onCompositionStart,
						onCompositionend: onCompositionEnd,
						onKeydown: [
							withKeys(withModifiers(onEnterPress, ["exact", "prevent"]), ["enter"]),
							withKeys(onArrowDown, ["down"]),
							withKeys(onArrowUp, ["up"]),
							withKeys(onEscape, ["esc"])
						],
						maxlength: unref(maxMessageLength),
						"aria-label": "輸入訊息",
						class: "w-full bg-transparent border-none outline-none py-2 px-2 text-base resize-none leading-relaxed overflow-hidden max-h-[200px]",
						style: { "color": "var(--text-primary)" },
						rows: "1",
						placeholder: isListening.value ? "聽取中..." : "對 server 下些指令"
					}, null, 40, _hoisted_14$1),
					activeSlashUsageHint.value ? (openBlock(), createElementBlock("div", _hoisted_15$1, " 提示：" + toDisplayString(activeSlashUsageHint.value), 1)) : createCommentVNode("", true),
					__props.userInput.length > unref(maxMessageLength) * .8 ? (openBlock(), createElementBlock("div", {
						key: 2,
						class: "px-2 pb-1 text-[11px] font-mono text-right",
						style: normalizeStyle({ color: __props.userInput.length >= unref(maxMessageLength) ? "var(--color-danger, #ef4444)" : "var(--text-tertiary)" })
					}, toDisplayString(__props.userInput.length) + " / " + toDisplayString(unref(maxMessageLength)), 5)) : createCommentVNode("", true)
				], 512), createBaseVNode("div", _hoisted_16$1, [createVNode(Transition, {
					name: "action-btn",
					mode: "out-in"
				}, {
					default: withCtx(() => [!__props.isProcessing && !__props.userInput.trim() ? (openBlock(), createElementBlock("button", {
						key: "slash",
						onClick: openSlashMenu,
						class: "action-btn-base opacity-50 hover:opacity-100",
						style: { "color": "var(--text-secondary)" },
						title: "Slash commands",
						"aria-label": "開啟指令選單"
					}, [..._cache[6] || (_cache[6] = [createBaseVNode("svg", {
						xmlns: "http://www.w3.org/2000/svg",
						class: "h-5 w-5",
						viewBox: "0 0 24 24",
						fill: "none",
						stroke: "currentColor",
						"stroke-width": "2.5",
						"stroke-linecap": "round"
					}, [createBaseVNode("line", {
						x1: "7",
						y1: "20",
						x2: "17",
						y2: "4"
					})], -1)])])) : createCommentVNode("", true)]),
					_: 1
				}), createVNode(Transition, {
					name: "action-btn",
					mode: "out-in"
				}, {
					default: withCtx(() => [__props.isProcessing ? (openBlock(), createElementBlock("button", {
						key: "stop",
						onClick: _cache[1] || (_cache[1] = ($event) => emit("stop")),
						class: "action-btn-base",
						style: { "color": "var(--text-secondary)" },
						title: "停止",
						"aria-label": "停止生成"
					}, [..._cache[7] || (_cache[7] = [createBaseVNode("svg", {
						xmlns: "http://www.w3.org/2000/svg",
						class: "h-5 w-5",
						viewBox: "0 0 24 24",
						fill: "currentColor"
					}, [createBaseVNode("rect", {
						x: "6",
						y: "6",
						width: "12",
						height: "12",
						rx: "2"
					})], -1)])])) : isListening.value ? (openBlock(), createElementBlock("button", {
						key: "listening",
						onClick: toggleVoice,
						class: "action-btn-base animate-pulse",
						style: {
							"background-color": "color-mix(in srgb, var(--accent-danger) 20%, transparent)",
							"color": "var(--accent-danger)"
						},
						title: "停止聆聽",
						"aria-label": "停止語音輸入"
					}, [..._cache[8] || (_cache[8] = [createBaseVNode("svg", {
						xmlns: "http://www.w3.org/2000/svg",
						class: "h-5 w-5",
						fill: "none",
						viewBox: "0 0 24 24",
						stroke: "currentColor"
					}, [createBaseVNode("path", {
						"stroke-linecap": "round",
						"stroke-linejoin": "round",
						"stroke-width": "2",
						d: "M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4m-4-8a3 3 0 01-3-3V5a3 3 0 116 0v6a3 3 0 01-3 3z"
					})], -1)])])) : __props.userInput.trim() ? (openBlock(), createElementBlock("button", {
						key: "send",
						onClick: _cache[2] || (_cache[2] = ($event) => __props.isOnline !== false && emit("send")),
						class: normalizeClass(["action-btn-base", __props.isOnline !== false ? "action-btn-send" : "opacity-30 cursor-not-allowed"]),
						style: normalizeStyle(__props.isOnline !== false ? "background-color: var(--accent-primary); color: white;" : "background-color: var(--bg-tertiary); color: var(--text-tertiary);"),
						title: __props.isOnline !== false ? "發送" : "離線中，無法發送",
						"aria-label": __props.isOnline !== false ? "發送訊息" : "離線中，無法發送"
					}, [..._cache[9] || (_cache[9] = [createBaseVNode("svg", {
						xmlns: "http://www.w3.org/2000/svg",
						class: "h-5 w-5",
						fill: "none",
						viewBox: "0 0 24 24",
						stroke: "currentColor",
						"stroke-width": "2"
					}, [createBaseVNode("path", {
						"stroke-linecap": "round",
						"stroke-linejoin": "round",
						d: "M4.5 10.5 12 3m0 0 7.5 7.5M12 3v18"
					})], -1)])], 14, _hoisted_17$1)) : (openBlock(), createElementBlock("button", {
						key: "mic",
						onClick: toggleVoice,
						class: "action-btn-base",
						style: { "color": "var(--text-tertiary)" },
						title: "語音輸入",
						"aria-label": "開始語音輸入"
					}, [..._cache[10] || (_cache[10] = [createBaseVNode("svg", {
						xmlns: "http://www.w3.org/2000/svg",
						class: "h-5 w-5",
						fill: "none",
						viewBox: "0 0 24 24",
						stroke: "currentColor"
					}, [createBaseVNode("path", {
						"stroke-linecap": "round",
						"stroke-linejoin": "round",
						"stroke-width": "2",
						d: "M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4m-4-8a3 3 0 01-3-3V5a3 3 0 116 0v6a3 3 0 01-3 3z"
					})], -1)])]))]),
					_: 1
				})])]),
				__props.isOnline === false ? (openBlock(), createElementBlock("div", _hoisted_18$1, [..._cache[11] || (_cache[11] = [createBaseVNode("span", null, "⚠ 伺服器離線，無法發送訊息", -1)])])) : createCommentVNode("", true),
				__props.statusMessage ? (openBlock(), createElementBlock("div", _hoisted_19$1, [createBaseVNode("span", _hoisted_20$1, [_cache[12] || (_cache[12] = createBaseVNode("span", { class: "mr-1" }, "⚡", -1)), createTextVNode(toDisplayString(__props.statusMessage), 1)])])) : createCommentVNode("", true)
			], 36);
		};
	}
};
var ControlPanel_default = /* @__PURE__ */ __plugin_vue_export_helper_default(_sfc_main$3, [["__scopeId", "data-v-0404011f"]]);

//#endregion
//#region src/utils/messageActions.js
/**
* Appends a quoted message block to the current draft.
* Keeps two trailing newlines so the user can continue typing immediately.
*
* @param {string} draft
* @param {string} messageContent
* @returns {string}
*/
function appendQuoteToDraft(draft, messageContent) {
	const normalizedDraft = typeof draft === "string" ? draft.trimEnd() : "";
	const normalizedMessage = typeof messageContent === "string" ? messageContent.trim() : "";
	if (!normalizedMessage) return normalizedDraft;
	const quoteBlock = normalizedMessage.split(/\r?\n/).map((line) => `> ${line}`).join("\n");
	if (!normalizedDraft) return `${quoteBlock}\n\n`;
	return `${normalizedDraft}\n\n${quoteBlock}\n\n`;
}

//#endregion
//#region src/components/MessageList.vue
var _hoisted_1$2 = {
	key: 0,
	class: "space-y-4",
	role: "status",
	"aria-live": "polite",
	"aria-busy": "true"
};
var _hoisted_2$2 = {
	key: 1,
	class: "chat-welcome-card",
	"aria-label": "首次使用引導"
};
var _hoisted_3$2 = { class: "welcome-shortcut-grid" };
var _hoisted_4$1 = ["disabled", "onClick"];
var _hoisted_5 = { class: "welcome-shortcut-head" };
var _hoisted_6 = {
	key: 2,
	class: "flex justify-center"
};
var _hoisted_7 = ["disabled"];
var _hoisted_8 = {
	key: 3,
	class: "message-mobile-hint"
};
var _hoisted_9 = ["data-msg-key"];
var _hoisted_10 = {
	key: 0,
	class: "text-xs font-bold text-white"
};
var _hoisted_11 = {
	key: 1,
	class: "text-xs font-bold text-white"
};
var _hoisted_12 = ["onTouchstartPassive"];
var _hoisted_13 = {
	class: "text-[11px] mb-1.5 flex justify-between items-center gap-4 font-medium tracking-wide",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_14 = { class: "message-inline-actions" };
var _hoisted_15 = ["onClick"];
var _hoisted_16 = ["onClick"];
var _hoisted_17 = ["innerHTML"];
var _hoisted_18 = {
	key: 0,
	class: "message-fade-mask"
};
var _hoisted_19 = {
	key: 0,
	class: "mt-1"
};
var _hoisted_20 = ["onClick"];
var _hoisted_21 = {
	key: 1,
	class: "mt-3"
};
var _hoisted_22 = {
	key: 2,
	class: "msg-aborted-banner"
};
var _hoisted_23 = ["onClick"];
var _hoisted_24 = {
	key: 4,
	class: "flex gap-3 items-start"
};
var _hoisted_25 = { class: "retry-banner-body" };
var _hoisted_26 = { class: "retry-countdown-text" };
var _hoisted_27 = ["aria-valuenow"];
var _hoisted_28 = { class: "retry-banner-actions" };
var _hoisted_29 = {
	class: "w-full px-4 md:px-[15%] pb-6 pt-3",
	style: { "background-color": "var(--bg-primary)" }
};
var TOUCH_DEVICE_QUERY = "(hover: none), (pointer: coarse)";
var LONG_PRESS_DELAY_MS = 420;
var LONG_PRESS_MOVE_TOLERANCE_PX = 14;
var DEFAULT_MAX_RENDER_LENGTH = 2e4;
var DEFAULT_WINDOW_SIZE = 80;
var DEFAULT_LOAD_CHUNK = 20;
var BOTTOM_STICKY_THRESHOLD = 80;
var FLOATING_STACK_GAP = 10;
var collapsedLines = 18;
var _sfc_main$2 = {
	__name: "MessageList",
	props: {
		messages: {
			type: Array,
			required: true
		},
		isProcessing: {
			type: Boolean,
			default: false
		},
		isAdmin: {
			type: Boolean,
			default: false
		},
		isOnline: {
			type: Boolean,
			default: true
		},
		userInput: {
			type: String,
			default: ""
		},
		model: {
			type: String,
			default: ""
		},
		availableModels: {
			type: Object,
			default: () => ({})
		},
		statusMessage: {
			type: String,
			default: ""
		},
		isRetrying: {
			type: Boolean,
			default: false
		},
		retryCountdown: {
			type: Object,
			default: () => ({
				active: false,
				type: "",
				remainingSec: 0,
				totalSec: 0
			})
		},
		onRetry: {
			type: Function,
			default: null
		},
		onCancelRetry: {
			type: Function,
			default: null
		},
		canRegenerateMessage: {
			type: Function,
			default: null
		},
		hasMoreFromServer: {
			type: Boolean,
			default: false
		},
		isHistoryLoading: {
			type: Boolean,
			default: false
		},
		isLoadingMore: {
			type: Boolean,
			default: false
		}
	},
	emits: [
		"command-action",
		"update:userInput",
		"update:model",
		"send",
		"stop",
		"edit-message",
		"regenerate-message",
		"load-more"
	],
	setup(__props, { emit: __emit }) {
		const props = __props;
		const emit = __emit;
		const QUICK_START_COMMANDS = Object.freeze([
			{
				cmd: "/status",
				label: "系統狀態",
				desc: "CPU、記憶體、磁碟總覽"
			},
			{
				cmd: "/docker",
				label: "Docker",
				desc: "快速查看容器與資源使用"
			},
			{
				cmd: "/help",
				label: "指令說明",
				desc: "列出可用指令與功能"
			}
		]);
		const isTouchDevice = ref(false);
		const messageActionMenu = ref({
			visible: false,
			messageContent: ""
		});
		let touchDeviceQuery = null;
		let longPressTimer = null;
		const longPressState = {
			tracking: false,
			startX: 0,
			startY: 0
		};
		const md = new lib_default({
			html: false,
			linkify: true,
			typographer: true
		});
		const defaultFence = md.renderer.rules.fence || function(tokens, idx, options, env, self) {
			return self.renderToken(tokens, idx, options);
		};
		md.renderer.rules.fence = (tokens, idx, options, env, self) => {
			const token = tokens[idx];
			const encodedCode = token.content.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
			const lang = token.info.trim().split(/\s+/)[0] || "";
			const langLabel = lang ? `<span class="code-block-lang">${md.utils.escapeHtml(lang)}</span>` : "";
			const codeHtml = defaultFence(tokens, idx, options, env, self);
			return "<div class=\"code-block-wrapper\"><div class=\"code-block-header\">" + langLabel + `<button type="button" class="copy-code-btn" data-code="${encodedCode}" title="複製程式碼"><svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg></button></div>` + codeHtml + `</div>`;
		};
		const MAX_RENDER_LENGTH = Number.isInteger(UI_CONFIG.markdownMaxRenderLength) && UI_CONFIG.markdownMaxRenderLength > 0 ? UI_CONFIG.markdownMaxRenderLength : DEFAULT_MAX_RENDER_LENGTH;
		const WINDOW_SIZE = Number.isInteger(UI_CONFIG.messageWindowSize) && UI_CONFIG.messageWindowSize > 0 ? UI_CONFIG.messageWindowSize : DEFAULT_WINDOW_SIZE;
		const LOAD_CHUNK = Number.isInteger(UI_CONFIG.messageWindowLoadChunk) && UI_CONFIG.messageWindowLoadChunk > 0 ? UI_CONFIG.messageWindowLoadChunk : DEFAULT_LOAD_CHUNK;
		const windowStart = ref(0);
		const hasHiddenMessages = computed(() => windowStart.value > 0 || props.hasMoreFromServer);
		const visibleMessages = computed(() => props.messages.slice(windowStart.value));
		const showHistorySkeleton = computed(() => props.isHistoryLoading && props.messages.length === 0);
		const showWelcomeCard = computed(() => !showHistorySkeleton.value && props.messages.length === 0);
		const quickStartDisabled = computed(() => props.isProcessing || props.isOnline === false);
		let pendingServerLoad = false;
		let scrollHeightBeforeLoad = 0;
		watch(() => props.isLoadingMore, (loading) => {
			if (loading) {
				pendingServerLoad = true;
				scrollHeightBeforeLoad = messageContainer.value?.scrollHeight ?? 0;
			} else if (pendingServerLoad) {
				pendingServerLoad = false;
				nextTick(() => {
					const el = messageContainer.value;
					if (el && scrollHeightBeforeLoad > 0) {
						el.scrollTop += el.scrollHeight - scrollHeightBeforeLoad;
						scrollHeightBeforeLoad = 0;
					}
				});
			}
		});
		watch(() => props.messages.length, (newLen, oldLen) => {
			if (newLen <= WINDOW_SIZE) {
				windowStart.value = 0;
				return;
			}
			if (pendingServerLoad) {
				const added = newLen - oldLen;
				windowStart.value = Math.min(newLen, windowStart.value + added);
				return;
			}
			const naturalStart = Math.max(0, oldLen - WINDOW_SIZE);
			if (windowStart.value >= naturalStart) windowStart.value = Math.max(0, newLen - WINDOW_SIZE);
		});
		const loadEarlierMessages = () => {
			if (windowStart.value === 0 && props.hasMoreFromServer) {
				emit("load-more");
				return;
			}
			const el = messageContainer.value;
			const prevScrollHeight = el?.scrollHeight ?? 0;
			windowStart.value = Math.max(0, windowStart.value - LOAD_CHUNK);
			nextTick(() => {
				if (el) el.scrollTop += el.scrollHeight - prevScrollHeight;
			});
		};
		const renderMarkdown = (text) => {
			if (!text) return "";
			if (text.length > MAX_RENDER_LENGTH) return "<p>[訊息過長，無法渲染，請使用複製功能查看原始內容]</p>";
			try {
				return purify.sanitize(md.render(text), {
					ADD_TAGS: ["button"],
					ADD_ATTR: ["data-code"],
					FORBID_TAGS: ["style"],
					FORBID_ATTR: [
						"style",
						"onerror",
						"onload",
						"onclick",
						"onmouseover"
					]
				});
			} catch (err) {
				console.error("Markdown rendering failed:", err);
				return "<p>[內容渲染失敗]</p>";
			}
		};
		const messageContainer = ref(null);
		const retryBannerRef = ref(null);
		let resizing = false;
		const isNearBottom = (threshold = BOTTOM_STICKY_THRESHOLD) => {
			const el = messageContainer.value;
			if (!el) return true;
			return el.scrollHeight - el.scrollTop - el.clientHeight < threshold;
		};
		const lockScrollToBottom = () => {
			const el = messageContainer.value;
			if (!el) return;
			resizing = true;
			el.scrollTop = el.scrollHeight;
			userAtBottom.value = true;
			requestAnimationFrame(() => {
				resizing = false;
			});
		};
		const scrollToBottom = () => {
			nextTick(() => {
				lockScrollToBottom();
			});
		};
		const userAtBottom = ref(true);
		const autoStickToBottom = ref(true);
		const onScroll = () => {
			if (resizing) return;
			const nearBottom = isNearBottom();
			userAtBottom.value = nearBottom;
			autoStickToBottom.value = nearBottom;
		};
		let scrollDebounceTimer = null;
		watch(() => props.messages.length, () => {
			if (!autoStickToBottom.value) return;
			if (scrollDebounceTimer) return;
			scrollDebounceTimer = requestAnimationFrame(() => {
				scrollDebounceTimer = null;
				if (autoStickToBottom.value) scrollToBottom();
			});
		});
		const handleCodeCopy = async (e) => {
			const btn = e.target.closest(".copy-code-btn");
			if (!btn) return;
			e.preventDefault();
			e.stopPropagation();
			let code = btn.dataset.code?.replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, "\"");
			if (!code) code = btn.closest(".code-block-wrapper")?.querySelector("pre code")?.textContent || "";
			if (!code) return;
			try {
				await writeTextToClipboard(code);
				btn.classList.add("copy-code-btn-copied");
				setTimeout(() => btn.classList.remove("copy-code-btn-copied"), 2e3);
			} catch (err) {
				console.error("Failed to copy code:", err);
				btn.classList.add("copy-code-btn-error");
				setTimeout(() => btn.classList.remove("copy-code-btn-error"), 2e3);
			}
		};
		let resizeObserver = null;
		onMounted(() => {
			if (typeof window !== "undefined" && typeof window.matchMedia === "function") {
				touchDeviceQuery = window.matchMedia(TOUCH_DEVICE_QUERY);
				syncTouchDeviceState(Boolean(touchDeviceQuery.matches));
				if (typeof touchDeviceQuery.addEventListener === "function") touchDeviceQuery.addEventListener("change", syncTouchDeviceState);
				else if (typeof touchDeviceQuery.addListener === "function") touchDeviceQuery.addListener(syncTouchDeviceState);
			}
			scrollToBottom();
			if (messageContainer.value) {
				messageContainer.value.addEventListener("scroll", onScroll, { passive: true });
				messageContainer.value.addEventListener("click", handleCodeCopy);
				resizeObserver = new ResizeObserver(() => {
					if (autoStickToBottom.value && messageContainer.value) lockScrollToBottom();
				});
				resizeObserver.observe(messageContainer.value);
			}
		});
		onBeforeUnmount(() => {
			if (touchDeviceQuery) {
				if (typeof touchDeviceQuery.removeEventListener === "function") touchDeviceQuery.removeEventListener("change", syncTouchDeviceState);
				else if (typeof touchDeviceQuery.removeListener === "function") touchDeviceQuery.removeListener(syncTouchDeviceState);
			}
			clearLongPressTracking();
			resizeObserver?.disconnect();
			retryBannerObserver?.disconnect();
			messageContainer.value?.removeEventListener("scroll", onScroll);
			messageContainer.value?.removeEventListener("click", handleCodeCopy);
		});
		const handleComposerResize = () => {
			if (!autoStickToBottom.value) return;
			scrollToBottom();
			requestAnimationFrame(lockScrollToBottom);
		};
		const handleComposerSend = () => {
			autoStickToBottom.value = true;
			userAtBottom.value = true;
			lockScrollToBottom();
			requestAnimationFrame(lockScrollToBottom);
			emit("send");
		};
		const runQuickStartCommand = async (command) => {
			if (!command || quickStartDisabled.value) return;
			emit("update:userInput", command);
			await nextTick();
			handleComposerSend();
		};
		const jumpToBottom = () => {
			autoStickToBottom.value = true;
			userAtBottom.value = true;
			lockScrollToBottom();
		};
		const showRetryBanner = computed(() => props.isRetrying && Boolean(props.statusMessage));
		const retryProgressPercent = computed(() => {
			const totalSec = Number(props.retryCountdown?.totalSec);
			const remainingSec = Number(props.retryCountdown?.remainingSec);
			if (!props.retryCountdown?.active || !Number.isFinite(totalSec) || totalSec <= 0) return 0;
			if (!Number.isFinite(remainingSec) || remainingSec <= 0) return 0;
			return Math.max(0, Math.min(100, remainingSec / totalSec * 100));
		});
		const retryBannerHeight = ref(0);
		const floatingOverlayVars = computed(() => ({ "--floating-stack-offset": showRetryBanner.value ? `${retryBannerHeight.value + FLOATING_STACK_GAP}px` : "0px" }));
		const measureRetryBanner = () => {
			retryBannerHeight.value = retryBannerRef.value?.offsetHeight ?? 0;
		};
		let retryBannerObserver = null;
		const bindRetryBannerRef = (el) => {
			if (retryBannerObserver && retryBannerRef.value) retryBannerObserver.unobserve(retryBannerRef.value);
			retryBannerRef.value = el;
			if (!el) {
				retryBannerHeight.value = 0;
				return;
			}
			measureRetryBanner();
			if (typeof ResizeObserver === "undefined") return;
			if (!retryBannerObserver) retryBannerObserver = new ResizeObserver(() => measureRetryBanner());
			retryBannerObserver.observe(el);
		};
		watch(showRetryBanner, async (visible) => {
			if (!visible) return;
			await nextTick();
			measureRetryBanner();
		});
		const writeTextToClipboard = async (text) => {
			if (navigator.clipboard?.writeText) try {
				await navigator.clipboard.writeText(text);
				return true;
			} catch (_) {}
			const textarea = document.createElement("textarea");
			textarea.value = text;
			textarea.setAttribute("readonly", "");
			textarea.style.position = "fixed";
			textarea.style.opacity = "0";
			textarea.style.pointerEvents = "none";
			textarea.style.left = "-9999px";
			document.body.appendChild(textarea);
			textarea.select();
			textarea.setSelectionRange(0, textarea.value.length);
			const copied = document.execCommand("copy");
			document.body.removeChild(textarea);
			if (copied) return true;
			throw new Error("無法存取剪貼簿，請檢查瀏覽器權限設定");
		};
		const copyToClipboard = async (text, event) => {
			if (!text) return;
			try {
				await writeTextToClipboard(text);
				const btn = event.currentTarget;
				btn.classList.add("msg-copy-btn-copied");
				setTimeout(() => btn.classList.remove("msg-copy-btn-copied"), 2e3);
			} catch (err) {
				console.error("Failed to copy:", err);
				const btn = event.currentTarget;
				btn.classList.add("msg-copy-btn-error");
				setTimeout(() => btn.classList.remove("msg-copy-btn-error"), 2e3);
			}
		};
		const syncTouchDeviceState = (matches = null) => {
			if (typeof matches === "boolean") {
				isTouchDevice.value = matches;
				return;
			}
			isTouchDevice.value = Boolean(touchDeviceQuery?.matches);
		};
		const clearLongPressTracking = () => {
			if (longPressTimer) {
				clearTimeout(longPressTimer);
				longPressTimer = null;
			}
			longPressState.tracking = false;
			longPressState.startX = 0;
			longPressState.startY = 0;
		};
		const closeMessageActionMenu = () => {
			messageActionMenu.value = {
				visible: false,
				messageContent: ""
			};
		};
		const shouldIgnoreLongPressTarget = (target) => {
			return Boolean(target?.closest("button, a, input, textarea, select, [role=\"button\"], [contenteditable=\"true\"], [data-no-long-press]"));
		};
		const handleMessageTouchStart = (event, messageContent) => {
			clearLongPressTracking();
			if (!isTouchDevice.value) return;
			if (!messageContent?.trim() || messageActionMenu.value.visible) return;
			if (event.touches.length !== 1 || shouldIgnoreLongPressTarget(event.target)) return;
			const touch = event.touches[0];
			longPressState.tracking = true;
			longPressState.startX = touch.clientX;
			longPressState.startY = touch.clientY;
			longPressTimer = setTimeout(() => {
				messageActionMenu.value = {
					visible: true,
					messageContent
				};
				clearLongPressTracking();
			}, LONG_PRESS_DELAY_MS);
		};
		const handleMessageTouchMove = (event) => {
			if (!longPressState.tracking || event.touches.length !== 1) return;
			const touch = event.touches[0];
			const deltaX = Math.abs(touch.clientX - longPressState.startX);
			const deltaY = Math.abs(touch.clientY - longPressState.startY);
			if (deltaX > LONG_PRESS_MOVE_TOLERANCE_PX || deltaY > LONG_PRESS_MOVE_TOLERANCE_PX) clearLongPressTracking();
		};
		const handleMessageTouchEnd = () => {
			clearLongPressTracking();
		};
		const copyFromMessageActionMenu = async () => {
			const text = messageActionMenu.value.messageContent;
			closeMessageActionMenu();
			if (!text?.trim()) return;
			try {
				await writeTextToClipboard(text);
			} catch (err) {
				console.error("Failed to copy from message action menu:", err);
			}
		};
		const quoteFromMessageActionMenu = () => {
			const text = messageActionMenu.value.messageContent;
			if (!text?.trim()) {
				closeMessageActionMenu();
				return;
			}
			emit("update:userInput", appendQuoteToDraft(props.userInput, text));
			closeMessageActionMenu();
		};
		const expandedStates = ref({});
		const messageKeyByObject = /* @__PURE__ */ new WeakMap();
		const usedMessageKeys = /* @__PURE__ */ new Set();
		let localMessageKeyCounter = 0;
		const lineCountOf = (text) => {
			if (!text) return 0;
			return text.split(/\r?\n/).length;
		};
		const hashText = (text) => {
			let hash = 2166136261;
			for (let i = 0; i < text.length; i += 1) {
				hash ^= text.charCodeAt(i);
				hash = Math.imul(hash, 16777619);
			}
			return (hash >>> 0).toString(36);
		};
		const buildMessageFingerprint = (msg, createdAt = "") => {
			const command = msg?.command;
			return [
				msg?.role ?? "",
				createdAt,
				msg?.content ?? "",
				command?.content ?? "",
				command?.status ?? "",
				command?.timeoutAt ?? "",
				msg?.aborted ? "aborted" : ""
			].join("|");
		};
		const allocateUniqueMessageKey = (baseKey) => {
			let key = baseKey;
			let suffix = 1;
			while (usedMessageKeys.has(key)) {
				suffix += 1;
				key = `${baseKey}#${suffix}`;
			}
			usedMessageKeys.add(key);
			return key;
		};
		const getMessageStableKey = (msg) => {
			if (!msg || typeof msg !== "object") return `primitive:${String(msg)}`;
			const existing = messageKeyByObject.get(msg);
			if (existing) return existing;
			const id = msg.id ?? msg.messageId ?? null;
			const createdAt = msg.createdAt ?? msg.command?.createdAt ?? "";
			const createdAtText = createdAt === null || createdAt === void 0 ? "" : String(createdAt).trim();
			const fingerprint = buildMessageFingerprint(msg, createdAtText);
			let baseKey = "";
			if (id !== null && id !== void 0 && String(id).trim()) baseKey = `msg:id:${String(id).trim()}`;
			else if (createdAtText) baseKey = `msg:createdAt:${createdAtText}:${hashText(fingerprint)}`;
			else {
				localMessageKeyCounter += 1;
				baseKey = `msg:local:${localMessageKeyCounter}:${hashText(fingerprint)}`;
			}
			const resolvedKey = allocateUniqueMessageKey(baseKey);
			messageKeyByObject.set(msg, resolvedKey);
			return resolvedKey;
		};
		const visibleMessagesWithKeys = computed(() => visibleMessages.value.map((msg, index) => ({
			msg,
			messageKey: getMessageStableKey(msg),
			absoluteIndex: windowStart.value + index
		})));
		watch(computed(() => props.messages.map(getMessageStableKey)), (keys) => {
			const activeKeys = new Set(keys);
			for (const key of Array.from(usedMessageKeys)) if (!activeKeys.has(key)) usedMessageKeys.delete(key);
			if (!Object.keys(expandedStates.value).length) return;
			expandedStates.value = Object.fromEntries(Object.entries(expandedStates.value).filter(([key]) => activeKeys.has(key)));
		});
		const shouldCollapseMessage = (msg) => {
			if (!msg || msg.role !== "ai") return false;
			const content = msg.content || "";
			if (!content.trim()) return false;
			if (msg.command) return false;
			return content.length > 1800 || lineCountOf(content) > 24;
		};
		const shouldShowEditAction = (entry) => entry?.msg?.role === "user" && !props.isProcessing;
		const shouldShowRegenerateAction = (entry) => {
			if (entry?.msg?.role !== "ai" || props.isProcessing) return false;
			if (typeof props.canRegenerateMessage === "function") return Boolean(props.canRegenerateMessage(entry.absoluteIndex));
			return true;
		};
		const isExpanded = (messageKey) => Boolean(expandedStates.value[messageKey]);
		const escapeAttributeSelector = (value) => {
			const text = String(value ?? "");
			if (typeof CSS !== "undefined" && typeof CSS.escape === "function") return CSS.escape(text);
			return text.replace(/\\/g, "\\\\").replace(/"/g, "\\\"");
		};
		const toggleExpand = (messageKey) => {
			const escapedMessageKey = escapeAttributeSelector(messageKey);
			const shell = messageContainer.value?.querySelector(`[data-msg-key="${escapedMessageKey}"] .message-content-shell`);
			const expanding = !expandedStates.value[messageKey];
			if (shell) if (expanding) {
				const from = shell.offsetHeight;
				shell.classList.remove("message-content-collapsed");
				const to = shell.scrollHeight;
				shell.style.maxHeight = from + "px";
				shell.style.overflow = "hidden";
				shell.offsetHeight;
				shell.style.transition = "max-height 0.35s cubic-bezier(0.4,0,0.2,1)";
				shell.style.maxHeight = to + "px";
				const onEnd = () => {
					shell.style.transition = "";
					shell.style.maxHeight = "";
					shell.style.overflow = "";
					shell.removeEventListener("transitionend", onEnd);
				};
				shell.addEventListener("transitionend", onEnd, { once: true });
			} else {
				const from = shell.scrollHeight;
				const to = (parseFloat(getComputedStyle(shell).lineHeight) || 1.7 * 16) * collapsedLines;
				shell.style.maxHeight = from + "px";
				shell.style.overflow = "hidden";
				shell.offsetHeight;
				shell.style.transition = "max-height 0.35s cubic-bezier(0.4,0,0.2,1)";
				shell.style.maxHeight = to + "px";
				const onEnd = () => {
					shell.style.transition = "";
					shell.style.maxHeight = "";
					shell.style.overflow = "";
					shell.classList.add("message-content-collapsed");
					shell.removeEventListener("transitionend", onEnd);
				};
				shell.addEventListener("transitionend", onEnd, { once: true });
			}
			expandedStates.value = {
				...expandedStates.value,
				[messageKey]: expanding
			};
		};
		return (_ctx, _cache) => {
			return openBlock(), createElementBlock("div", {
				class: "flex flex-col h-full relative min-w-0 floating-stack-root",
				style: normalizeStyle(floatingOverlayVars.value)
			}, [
				createBaseVNode("div", {
					ref_key: "messageContainer",
					ref: messageContainer,
					class: "message-list-container flex-1 overflow-y-auto p-4 md:p-6 space-y-6 custom-scrollbar",
					role: "log",
					"aria-label": "對話訊息"
				}, [
					showHistorySkeleton.value ? (openBlock(), createElementBlock("div", _hoisted_1$2, [..._cache[6] || (_cache[6] = [createStaticVNode("<div class=\"inline-flex items-center gap-2 px-3 py-1 rounded-full text-xs font-medium animate-pulse\" style=\"background-color:var(--bg-secondary);color:var(--text-tertiary);border:1px solid var(--border-primary);\"><span>載入對話紀錄中...</span></div><div class=\"flex gap-3 items-start\"><div class=\"flex-shrink-0 w-9 h-9 rounded-xl animate-pulse\" style=\"background:color-mix(in srgb, var(--accent-primary) 25%, var(--bg-secondary));\"></div><div class=\"flex-1 max-w-[80%] rounded-2xl p-4 border animate-pulse\" style=\"background-color:var(--ai-bubble);border-color:var(--border-primary);\"><div class=\"h-3.5 w-24 rounded mb-3\" style=\"background-color:color-mix(in srgb, var(--text-tertiary) 20%, transparent);\"></div><div class=\"h-3.5 w-full rounded mb-2\" style=\"background-color:color-mix(in srgb, var(--text-tertiary) 16%, transparent);\"></div><div class=\"h-3.5 w-5/6 rounded\" style=\"background-color:color-mix(in srgb, var(--text-tertiary) 16%, transparent);\"></div></div></div><div class=\"flex gap-3 items-start flex-row-reverse\"><div class=\"flex-shrink-0 w-9 h-9 rounded-xl animate-pulse\" style=\"background:color-mix(in srgb, var(--accent-primary) 20%, var(--bg-secondary));\"></div><div class=\"flex-1 max-w-[72%] rounded-2xl p-4 border animate-pulse\" style=\"background-color:var(--user-bubble);border-color:var(--border-primary);\"><div class=\"h-3.5 w-20 rounded mb-3\" style=\"background-color:color-mix(in srgb, var(--text-tertiary) 20%, transparent);\"></div><div class=\"h-3.5 w-full rounded\" style=\"background-color:color-mix(in srgb, var(--text-tertiary) 16%, transparent);\"></div></div></div>", 3)])])) : createCommentVNode("", true),
					showWelcomeCard.value ? (openBlock(), createElementBlock("section", _hoisted_2$2, [
						_cache[7] || (_cache[7] = createBaseVNode("div", { class: "welcome-badge" }, "歡迎使用 Server Assistant", -1)),
						_cache[8] || (_cache[8] = createBaseVNode("h2", { class: "welcome-title" }, "先試試常用斜線指令", -1)),
						_cache[9] || (_cache[9] = createBaseVNode("p", { class: "welcome-description" }, " 系統會直接由後端執行這些查詢，不經 AI 推理，回應更快也更穩定。 ", -1)),
						createBaseVNode("div", _hoisted_3$2, [(openBlock(true), createElementBlock(Fragment, null, renderList(unref(QUICK_START_COMMANDS), (item) => {
							return openBlock(), createElementBlock("button", {
								key: item.cmd,
								type: "button",
								class: "welcome-shortcut-btn",
								disabled: quickStartDisabled.value,
								onClick: ($event) => runQuickStartCommand(item.cmd)
							}, [createBaseVNode("div", _hoisted_5, [createBaseVNode("code", null, toDisplayString(item.cmd), 1), createBaseVNode("span", null, toDisplayString(item.label), 1)]), createBaseVNode("p", null, toDisplayString(item.desc), 1)], 8, _hoisted_4$1);
						}), 128))]),
						_cache[10] || (_cache[10] = createBaseVNode("p", { class: "welcome-tip" }, [
							createTextVNode(" 也可以直接輸入自然語言，或使用 "),
							createBaseVNode("code", null, "!<Linux 指令>"),
							createTextVNode("（例如 "),
							createBaseVNode("code", null, "!docker ps"),
							createTextVNode("）。 ")
						], -1))
					])) : createCommentVNode("", true),
					hasHiddenMessages.value ? (openBlock(), createElementBlock("div", _hoisted_6, [createBaseVNode("button", {
						type: "button",
						onClick: loadEarlierMessages,
						disabled: __props.isLoadingMore,
						class: "px-4 py-1.5 text-xs font-medium rounded-full border transition-all hover:scale-105 disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:scale-100",
						style: {
							"background-color": "var(--bg-secondary)",
							"color": "var(--text-tertiary)",
							"border-color": "var(--border-primary)"
						}
					}, [__props.isLoadingMore ? (openBlock(), createElementBlock(Fragment, { key: 0 }, [createTextVNode("載入中...")], 64)) : windowStart.value > 0 ? (openBlock(), createElementBlock(Fragment, { key: 1 }, [createTextVNode("載入更早訊息（還有 " + toDisplayString(windowStart.value) + " 則）", 1)], 64)) : (openBlock(), createElementBlock(Fragment, { key: 2 }, [createTextVNode("從資料庫載入更多訊息")], 64))], 8, _hoisted_7)])) : createCommentVNode("", true),
					isTouchDevice.value && visibleMessagesWithKeys.value.length > 0 ? (openBlock(), createElementBlock("div", _hoisted_8, " 長按訊息可快速「複製」或「引用」 ")) : createCommentVNode("", true),
					(openBlock(true), createElementBlock(Fragment, null, renderList(visibleMessagesWithKeys.value, (entry) => {
						return openBlock(), createElementBlock("div", {
							key: entry.messageKey,
							"data-msg-key": entry.messageKey,
							class: normalizeClass(["flex gap-3 items-start group", entry.msg.role === "user" ? "flex-row-reverse" : "flex-row"])
						}, [
							createBaseVNode("div", { class: normalizeClass(["flex-shrink-0 w-9 h-9 rounded-xl flex items-center justify-center overflow-hidden select-none shadow-sm", entry.msg.role === "user" ? "bg-gradient-to-br from-indigo-500 to-purple-600" : "bg-gradient-to-br from-emerald-500 to-teal-600"]) }, [entry.msg.role === "user" ? (openBlock(), createElementBlock("span", _hoisted_10, "U")) : (openBlock(), createElementBlock("span", _hoisted_11, "AI"))], 2),
							createBaseVNode("div", {
								class: "max-w-[85%] min-w-0 rounded-2xl p-4 shadow-sm transition-all duration-200",
								onTouchstartPassive: ($event) => handleMessageTouchStart($event, entry.msg.content),
								onTouchmovePassive: handleMessageTouchMove,
								onTouchend: handleMessageTouchEnd,
								onTouchcancel: handleMessageTouchEnd,
								style: normalizeStyle(entry.msg.role === "user" ? {
									backgroundColor: "var(--user-bubble)",
									color: "var(--text-primary)"
								} : {
									backgroundColor: "var(--ai-bubble)",
									border: "1px solid var(--border-primary)"
								})
							}, [
								createBaseVNode("div", _hoisted_13, [createBaseVNode("span", null, toDisplayString(entry.msg.role === "user" ? "User" : "AI Assistant"), 1), createBaseVNode("div", _hoisted_14, [shouldShowEditAction(entry) ? (openBlock(), createElementBlock("button", {
									key: 0,
									type: "button",
									class: "message-inline-action-btn",
									title: "編輯訊息",
									"aria-label": "編輯訊息",
									onClick: ($event) => emit("edit-message", entry.absoluteIndex)
								}, " 編輯 ", 8, _hoisted_15)) : createCommentVNode("", true), shouldShowRegenerateAction(entry) ? (openBlock(), createElementBlock("button", {
									key: 1,
									type: "button",
									class: "message-inline-action-btn",
									title: "重新生成回覆",
									"aria-label": "重新生成回覆",
									onClick: ($event) => emit("regenerate-message", entry.absoluteIndex)
								}, " 重新生成 ", 8, _hoisted_16)) : createCommentVNode("", true)])]),
								createBaseVNode("div", {
									class: normalizeClass(["message-content-shell", { "message-content-collapsed": shouldCollapseMessage(entry.msg) && !isExpanded(entry.messageKey) }]),
									style: normalizeStyle({ "--collapsed-lines": String(collapsedLines) })
								}, [createBaseVNode("div", {
									class: "markdown-content leading-7 text-[15px]",
									style: { "color": "var(--text-primary)" },
									innerHTML: renderMarkdown(entry.msg.content)
								}, null, 8, _hoisted_17), shouldCollapseMessage(entry.msg) && !isExpanded(entry.messageKey) ? (openBlock(), createElementBlock("div", _hoisted_18)) : createCommentVNode("", true)], 6),
								shouldCollapseMessage(entry.msg) ? (openBlock(), createElementBlock("div", _hoisted_19, [createBaseVNode("button", {
									type: "button",
									class: "message-expand-btn",
									onClick: ($event) => toggleExpand(entry.messageKey)
								}, [(openBlock(), createElementBlock("svg", {
									class: normalizeClass(["message-expand-icon", { "message-expand-icon-open": isExpanded(entry.messageKey) }]),
									viewBox: "0 0 16 16",
									fill: "currentColor"
								}, [..._cache[11] || (_cache[11] = [createBaseVNode("path", { d: "M4.646 5.646a.5.5 0 0 1 .708 0L8 8.293l2.646-2.647a.5.5 0 0 1 .708.708l-3 3a.5.5 0 0 1-.708 0l-3-3a.5.5 0 0 1 0-.708z" }, null, -1)])], 2)), createBaseVNode("span", null, toDisplayString(isExpanded(entry.messageKey) ? "收合" : "展開完整輸出"), 1)], 8, _hoisted_20)])) : createCommentVNode("", true),
								entry.msg.command ? (openBlock(), createElementBlock("div", _hoisted_21, [createVNode(ChatCommandRequest_default, {
									command: entry.msg.command.content,
									status: entry.msg.command.status,
									disabled: Boolean(entry.msg.command.inFlight) || __props.isProcessing,
									"created-at": entry.msg.command.createdAt,
									"resolved-at": entry.msg.command.resolvedAt,
									"expires-at": entry.msg.command.timeoutAt,
									"ttl-seconds": unref(COMMAND_CONFIRM_TIMEOUT_SECONDS),
									onConfirm: ($event) => emit("command-action", entry.msg, "confirm"),
									onCancel: ($event) => emit("command-action", entry.msg, "cancel")
								}, null, 8, [
									"command",
									"status",
									"disabled",
									"created-at",
									"resolved-at",
									"expires-at",
									"ttl-seconds",
									"onConfirm",
									"onCancel"
								])])) : createCommentVNode("", true),
								entry.msg.aborted ? (openBlock(), createElementBlock("div", _hoisted_22, [..._cache[12] || (_cache[12] = [createBaseVNode("svg", {
									xmlns: "http://www.w3.org/2000/svg",
									width: "12",
									height: "12",
									viewBox: "0 0 24 24",
									fill: "none",
									stroke: "currentColor",
									"stroke-width": "2.5",
									"stroke-linecap": "round",
									"stroke-linejoin": "round"
								}, [
									createBaseVNode("circle", {
										cx: "12",
										cy: "12",
										r: "10"
									}),
									createBaseVNode("line", {
										x1: "15",
										y1: "9",
										x2: "9",
										y2: "15"
									}),
									createBaseVNode("line", {
										x1: "9",
										y1: "9",
										x2: "15",
										y2: "15"
									})
								], -1), createTextVNode(" 已中斷回應 ", -1)])])) : createCommentVNode("", true)
							], 44, _hoisted_12),
							!isTouchDevice.value ? (openBlock(), createElementBlock("button", {
								key: 0,
								onClick: ($event) => copyToClipboard(entry.msg.content, $event),
								class: "mt-2 p-1.5 rounded-lg transition-all opacity-100 sm:opacity-0 sm:group-hover:opacity-100 focus:opacity-100",
								style: { "color": "var(--text-tertiary)" },
								title: "複製內容",
								"aria-label": "複製訊息內容"
							}, [..._cache[13] || (_cache[13] = [createBaseVNode("svg", {
								xmlns: "http://www.w3.org/2000/svg",
								class: "h-4 w-4",
								fill: "none",
								viewBox: "0 0 24 24",
								stroke: "currentColor"
							}, [createBaseVNode("path", {
								"stroke-linecap": "round",
								"stroke-linejoin": "round",
								"stroke-width": "2",
								d: "M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"
							})], -1)])], 8, _hoisted_23)) : createCommentVNode("", true)
						], 10, _hoisted_9);
					}), 128)),
					__props.isProcessing ? (openBlock(), createElementBlock("div", _hoisted_24, [..._cache[14] || (_cache[14] = [createStaticVNode("<div class=\"flex-shrink-0 w-9 h-9 rounded-xl bg-gradient-to-br from-emerald-500 to-teal-600 flex items-center justify-center overflow-hidden select-none shadow-sm\"><span class=\"text-xs font-bold text-white\">AI</span></div><div class=\"rounded-2xl p-4 border flex items-center gap-1.5 shadow-sm\" style=\"background-color:var(--ai-bubble);border-color:var(--border-primary);\"><div class=\"w-2 h-2 rounded-full animate-bounce\" style=\"background-color:var(--text-tertiary);\"></div><div class=\"w-2 h-2 rounded-full animate-bounce [animation-delay:75ms]\" style=\"background-color:var(--text-tertiary);\"></div><div class=\"w-2 h-2 rounded-full animate-bounce [animation-delay:150ms]\" style=\"background-color:var(--text-tertiary);\"></div></div>", 2)])])) : createCommentVNode("", true)
				], 512),
				createVNode(Transition, { name: "message-action-menu" }, {
					default: withCtx(() => [messageActionMenu.value.visible ? (openBlock(), createElementBlock("div", {
						key: 0,
						class: "message-action-backdrop",
						onClick: closeMessageActionMenu
					}, [createBaseVNode("div", {
						class: "message-action-sheet",
						role: "dialog",
						"aria-modal": "true",
						"aria-label": "訊息操作選單",
						onClick: _cache[0] || (_cache[0] = withModifiers(() => {}, ["stop"]))
					}, [
						createBaseVNode("button", {
							type: "button",
							class: "message-action-btn",
							onClick: copyFromMessageActionMenu
						}, " 複製訊息 "),
						createBaseVNode("button", {
							type: "button",
							class: "message-action-btn",
							onClick: quoteFromMessageActionMenu
						}, " 引用到輸入框 "),
						createBaseVNode("button", {
							type: "button",
							class: "message-action-btn message-action-btn-cancel",
							onClick: closeMessageActionMenu
						}, " 取消 ")
					])])) : createCommentVNode("", true)]),
					_: 1
				}),
				createVNode(Transition, { name: "retry-banner" }, {
					default: withCtx(() => [showRetryBanner.value ? (openBlock(), createElementBlock("div", {
						key: 0,
						ref: bindRetryBannerRef,
						class: "retry-countdown-banner"
					}, [createBaseVNode("div", _hoisted_25, [createBaseVNode("span", _hoisted_26, toDisplayString(__props.statusMessage), 1), createBaseVNode("div", {
						class: "retry-progress-track",
						role: "progressbar",
						"aria-valuemin": "0",
						"aria-valuemax": "100",
						"aria-valuenow": Math.round(retryProgressPercent.value)
					}, [createBaseVNode("div", {
						class: "retry-progress-fill",
						style: normalizeStyle({ width: `${retryProgressPercent.value}%` })
					}, null, 4)], 8, _hoisted_27)]), createBaseVNode("div", _hoisted_28, [__props.onRetry ? (openBlock(), createElementBlock("button", {
						key: 0,
						type: "button",
						class: "retry-now-btn",
						onClick: _cache[1] || (_cache[1] = (...args) => __props.onRetry && __props.onRetry(...args))
					}, "立即重試")) : createCommentVNode("", true), __props.onCancelRetry ? (openBlock(), createElementBlock("button", {
						key: 1,
						type: "button",
						class: "retry-cancel-btn",
						onClick: _cache[2] || (_cache[2] = (...args) => __props.onCancelRetry && __props.onCancelRetry(...args))
					}, "取消")) : createCommentVNode("", true)])])) : createCommentVNode("", true)]),
					_: 1
				}),
				createVNode(Transition, { name: "jump-btn" }, {
					default: withCtx(() => [!userAtBottom.value ? (openBlock(), createElementBlock("button", {
						key: 0,
						type: "button",
						onClick: jumpToBottom,
						class: "jump-to-bottom-btn",
						title: "返回最新訊息",
						"aria-label": "返回最新訊息"
					}, [..._cache[15] || (_cache[15] = [createBaseVNode("svg", {
						xmlns: "http://www.w3.org/2000/svg",
						width: "16",
						height: "16",
						viewBox: "0 0 24 24",
						fill: "none",
						stroke: "currentColor",
						"stroke-width": "2.5",
						"stroke-linecap": "round",
						"stroke-linejoin": "round"
					}, [createBaseVNode("path", { d: "M12 5v14M5 12l7 7 7-7" })], -1), createBaseVNode("span", null, "返回最新訊息", -1)])])) : createCommentVNode("", true)]),
					_: 1
				}),
				createBaseVNode("footer", _hoisted_29, [createVNode(ControlPanel_default, {
					model: __props.model,
					"onUpdate:model": _cache[3] || (_cache[3] = (val) => emit("update:model", val)),
					availableModels: __props.availableModels,
					isAdmin: __props.isAdmin,
					isOnline: __props.isOnline,
					userInput: __props.userInput,
					"onUpdate:userInput": _cache[4] || (_cache[4] = (val) => emit("update:userInput", val)),
					isProcessing: __props.isProcessing,
					statusMessage: __props.isRetrying ? "" : __props.statusMessage,
					onComposerResize: handleComposerResize,
					onSend: handleComposerSend,
					onStop: _cache[5] || (_cache[5] = ($event) => emit("stop"))
				}, null, 8, [
					"model",
					"availableModels",
					"isAdmin",
					"isOnline",
					"userInput",
					"isProcessing",
					"statusMessage"
				]), _cache[16] || (_cache[16] = createBaseVNode("p", {
					class: "text-[10px] text-center mt-3",
					style: { "color": "var(--text-tertiary)" }
				}, "Groq x Spring AI", -1))])
			], 4);
		};
	}
};
var MessageList_default = _sfc_main$2;

//#endregion
//#region src/components/ChatInterfaceLayout.vue
var _hoisted_1$1 = { class: "flex-1 flex flex-col h-full relative min-w-0" };
var _hoisted_2$1 = {
	class: "text-xs ml-3 px-2.5 py-1 rounded-full font-mono border",
	style: {
		"background-color": "var(--bg-tertiary)",
		"color": "var(--text-secondary)",
		"border-color": "var(--border-primary)"
	}
};
var _hoisted_3$1 = {
	class: "text-xs ml-2 px-2.5 py-1 rounded-full font-mono border",
	style: {
		"background-color": "color-mix(in srgb, var(--accent-primary) 15%, transparent)",
		"color": "var(--accent-primary)",
		"border-color": "color-mix(in srgb, var(--accent-primary) 30%, transparent)"
	}
};
var _sfc_main$1 = {
	__name: "ChatInterfaceLayout",
	props: {
		isMobileViewport: {
			type: Boolean,
			required: true
		},
		isSidebarOpen: {
			type: Boolean,
			required: true
		},
		conversations: {
			type: Array,
			required: true
		},
		currentConversationId: {
			type: String,
			default: ""
		},
		serverIp: {
			type: String,
			default: ""
		},
		isOnline: {
			type: Boolean,
			required: true
		},
		displayModelName: {
			type: String,
			default: ""
		},
		currentUser: {
			type: String,
			default: ""
		},
		isAdmin: {
			type: Boolean,
			default: false
		},
		messages: {
			type: Array,
			required: true
		},
		isProcessing: {
			type: Boolean,
			required: true
		},
		userInput: {
			type: String,
			default: ""
		},
		model: {
			type: String,
			default: ""
		},
		availableModels: {
			type: Object,
			default: () => ({})
		},
		statusMessage: {
			type: String,
			default: ""
		},
		isRetrying: {
			type: Boolean,
			default: false
		},
		retryCountdown: {
			type: Object,
			default: () => ({})
		},
		onRetry: {
			type: Function,
			default: null
		},
		onCancelRetry: {
			type: Function,
			default: null
		},
		canRegenerateMessage: {
			type: Function,
			default: null
		},
		hasMoreFromServer: {
			type: Boolean,
			default: false
		},
		isHistoryLoading: {
			type: Boolean,
			default: false
		},
		isLoadingMore: {
			type: Boolean,
			default: false
		}
	},
	emits: [
		"touchstart",
		"touchmove",
		"touchend",
		"touchcancel",
		"close-sidebar",
		"new-chat",
		"select-chat",
		"delete-chat",
		"export-chat",
		"toggle-sidebar",
		"open-admin",
		"logout",
		"command-action",
		"update:userInput",
		"update:model",
		"send",
		"stop",
		"edit-message",
		"regenerate-message",
		"load-more"
	],
	setup(__props, { expose: __expose, emit: __emit }) {
		const sidebarRef = ref(null);
		__expose({ focusSearchInput() {
			return sidebarRef.value?.focusSearchInput?.();
		} });
		return (_ctx, _cache) => {
			return openBlock(), createElementBlock("div", {
				class: "flex h-full overflow-hidden relative",
				onTouchstartPassive: _cache[16] || (_cache[16] = ($event) => _ctx.$emit("touchstart", $event)),
				onTouchmovePassive: _cache[17] || (_cache[17] = ($event) => _ctx.$emit("touchmove", $event)),
				onTouchend: _cache[18] || (_cache[18] = ($event) => _ctx.$emit("touchend", $event)),
				onTouchcancel: _cache[19] || (_cache[19] = ($event) => _ctx.$emit("touchcancel", $event))
			}, [
				createVNode(Transition, { name: "sidebar-backdrop" }, {
					default: withCtx(() => [__props.isMobileViewport && __props.isSidebarOpen ? (openBlock(), createElementBlock("button", {
						key: 0,
						type: "button",
						class: "mobile-sidebar-backdrop absolute inset-0 z-30",
						"aria-label": "關閉側邊欄",
						onClick: _cache[0] || (_cache[0] = ($event) => _ctx.$emit("close-sidebar"))
					})) : createCommentVNode("", true)]),
					_: 1
				}),
				createVNode(Sidebar_default, {
					ref_key: "sidebarRef",
					ref: sidebarRef,
					class: normalizeClass(__props.isMobileViewport ? "mobile-sidebar-panel absolute inset-y-0 left-0 z-40 shadow-2xl" : ""),
					isOpen: __props.isSidebarOpen,
					conversations: __props.conversations,
					currentId: __props.currentConversationId,
					onNewChat: _cache[1] || (_cache[1] = ($event) => _ctx.$emit("new-chat")),
					onSelectChat: _cache[2] || (_cache[2] = ($event) => _ctx.$emit("select-chat", $event)),
					onDeleteChat: _cache[3] || (_cache[3] = ($event) => _ctx.$emit("delete-chat", $event)),
					onExportChat: _cache[4] || (_cache[4] = ($event) => _ctx.$emit("export-chat", $event))
				}, null, 8, [
					"class",
					"isOpen",
					"conversations",
					"currentId"
				]),
				createBaseVNode("div", _hoisted_1$1, [createVNode(ChatHeader_default, {
					ip: __props.serverIp,
					isOnline: __props.isOnline,
					onToggleSidebar: _cache[7] || (_cache[7] = ($event) => _ctx.$emit("toggle-sidebar"))
				}, {
					"model-name": withCtx(() => [
						createBaseVNode("span", _hoisted_2$1, toDisplayString(__props.displayModelName), 1),
						createBaseVNode("span", _hoisted_3$1, toDisplayString(__props.currentUser), 1),
						__props.isAdmin ? (openBlock(), createElementBlock("button", {
							key: 0,
							onClick: _cache[5] || (_cache[5] = ($event) => _ctx.$emit("open-admin")),
							class: "ml-2 text-xs px-2.5 py-1 rounded-full border transition-all hover:scale-105",
							style: {
								"background-color": "var(--bg-secondary)",
								"color": "var(--text-secondary)",
								"border-color": "var(--border-primary)"
							},
							"aria-label": "開啟管理面板"
						}, " Admin ")) : createCommentVNode("", true)
					]),
					actions: withCtx(() => [createBaseVNode("button", {
						onClick: _cache[6] || (_cache[6] = ($event) => _ctx.$emit("logout")),
						class: "flex items-center gap-2 px-3 py-2 text-xs font-medium rounded-lg border transition-all group",
						style: {
							"background-color": "var(--bg-secondary)",
							"color": "var(--text-primary)",
							"border-color": "var(--border-primary)"
						},
						title: "登出系統",
						"aria-label": "登出系統"
					}, [..._cache[20] || (_cache[20] = [createBaseVNode("span", null, "登出", -1), createBaseVNode("svg", {
						xmlns: "http://www.w3.org/2000/svg",
						class: "h-3.5 w-3.5 transition-transform group-hover:translate-x-0.5",
						fill: "none",
						viewBox: "0 0 24 24",
						stroke: "currentColor"
					}, [createBaseVNode("path", {
						"stroke-linecap": "round",
						"stroke-linejoin": "round",
						"stroke-width": "2",
						d: "M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1"
					})], -1)])])]),
					_: 1
				}, 8, ["ip", "isOnline"]), createVNode(MessageList_default, {
					class: "flex-1 min-h-0",
					messages: __props.messages,
					isProcessing: __props.isProcessing,
					isAdmin: __props.isAdmin,
					isOnline: __props.isOnline,
					onCommandAction: _cache[8] || (_cache[8] = (msg, action) => _ctx.$emit("command-action", {
						msg,
						action
					})),
					userInput: __props.userInput,
					"onUpdate:userInput": _cache[9] || (_cache[9] = ($event) => _ctx.$emit("update:userInput", $event)),
					model: __props.model,
					"onUpdate:model": _cache[10] || (_cache[10] = ($event) => _ctx.$emit("update:model", $event)),
					availableModels: __props.availableModels,
					statusMessage: __props.statusMessage,
					isRetrying: __props.isRetrying,
					retryCountdown: __props.retryCountdown,
					onRetry: __props.onRetry,
					onCancelRetry: __props.onCancelRetry,
					canRegenerateMessage: __props.canRegenerateMessage,
					hasMoreFromServer: __props.hasMoreFromServer,
					isHistoryLoading: __props.isHistoryLoading,
					isLoadingMore: __props.isLoadingMore,
					onSend: _cache[11] || (_cache[11] = ($event) => _ctx.$emit("send", $event)),
					onStop: _cache[12] || (_cache[12] = ($event) => _ctx.$emit("stop")),
					onEditMessage: _cache[13] || (_cache[13] = ($event) => _ctx.$emit("edit-message", $event)),
					onRegenerateMessage: _cache[14] || (_cache[14] = ($event) => _ctx.$emit("regenerate-message", $event)),
					onLoadMore: _cache[15] || (_cache[15] = ($event) => _ctx.$emit("load-more"))
				}, null, 8, [
					"messages",
					"isProcessing",
					"isAdmin",
					"isOnline",
					"userInput",
					"model",
					"availableModels",
					"statusMessage",
					"isRetrying",
					"retryCountdown",
					"onRetry",
					"onCancelRetry",
					"canRegenerateMessage",
					"hasMoreFromServer",
					"isHistoryLoading",
					"isLoadingMore"
				])])
			], 32);
		};
	}
};
var ChatInterfaceLayout_default = /* @__PURE__ */ __plugin_vue_export_helper_default(_sfc_main$1, [["__scopeId", "data-v-f400d99b"]]);

//#endregion
//#region src/stores/systemStore.js
/**
* System Store
*
* Manages system information, available models, and UI state
*/
const useSystemStore = defineStore("system", () => {
	const serverIp = ref("Loading...");
	const availableModels = ref({});
	const statusMessage = ref("");
	const showAdmin = ref(false);
	/**
	* Load system information
	*/
	async function loadSystemInfo() {
		try {
			serverIp.value = (await systemApi.getInfo()).ip || "Unknown";
		} catch (error) {
			console.error("Load system info error:", error);
			serverIp.value = "Error";
		}
	}
	/**
	* Load available AI models
	*/
	async function loadModels() {
		try {
			const result = await chatApi.getModels();
			if (result.success) availableModels.value = result.data || {};
		} catch (error) {
			console.error("Load models error:", error);
		}
	}
	/**
	* Set status message
	* @param {string} message - Status message to display
	*/
	function setStatusMessage(message) {
		statusMessage.value = message;
	}
	/**
	* Clear status message
	*/
	function clearStatusMessage() {
		statusMessage.value = "";
	}
	/**
	* Open admin dashboard
	*/
	function openAdmin() {
		showAdmin.value = true;
	}
	/**
	* Close admin dashboard
	*/
	function closeAdmin() {
		showAdmin.value = false;
	}
	/**
	* Initialize system data (models and info)
	*/
	async function initialize() {
		await Promise.all([loadModels(), loadSystemInfo()]);
	}
	return {
		serverIp,
		availableModels,
		statusMessage,
		showAdmin,
		loadSystemInfo,
		loadModels,
		setStatusMessage,
		clearStatusMessage,
		openAdmin,
		closeAdmin,
		initialize
	};
});

//#endregion
//#region src/utils/commandMarkers.js
const COMMAND_MARKERS = Object.freeze({
	CMD_PREFIX: "[CMD:::",
	CONFIRM_CMD_PREFIX: "[CONFIRM_CMD:::",
	RESOLVED_CMD_PREFIX: "[RESOLVED_CMD:::",
	CMD_SUFFIX: ":::]",
	OFFLOAD_JOB_PREFIX: "[OFFLOAD_JOB:::",
	BG_JOB_PREFIX: "[BG_JOB:::",
	RATE_LIMIT_PREFIX: "[RATE_LIMIT:::"
});
var COMMAND_MARKER_REGEX = /\[(?:CONFIRM_)?CMD:::([\s\S]+?):::\]/;
var RESOLVED_COMMAND_MARKER_REGEX = /\n?\[RESOLVED_CMD:::(confirmed|cancelled):::([\s\S]+?):::\]/;
var OFFLOAD_JOB_MARKER_REGEX = /\[OFFLOAD_JOB:::(.*?):::\]/;
var BG_JOB_MARKER_REGEX = /\[BG_JOB:::(.*?):::\]/;
var RATE_LIMIT_MARKER_REGEX = /\[RATE_LIMIT:::(\d+):::(?:(\d+):::)?\]/;
var CANCELLED_COMMAND_TEXT_REGEX = /^❌\s*已取消指令[：:]\s*([\s\S]+?)\s*$/;
var CONFIRMED_COMMAND_TEXT_REGEX = /^✅\s*已執行指令[：:]\s*([\s\S]+?)\s*$/;
function parseTimestampToMs(value) {
	if (typeof value === "number" && Number.isFinite(value)) return value;
	if (typeof value !== "string") return null;
	const trimmed = value.trim();
	if (!trimmed) return null;
	const parsed = Date.parse(trimmed);
	return Number.isFinite(parsed) ? parsed : null;
}
function extractMarkerBody(content, regex) {
	if (typeof content !== "string" || !content) return null;
	const match = content.match(regex);
	if (!match || !match[1]) return null;
	return match[1].trim() || null;
}
function extractCommandMarker(content) {
	if (typeof content !== "string" || !content) return null;
	const match = content.match(COMMAND_MARKER_REGEX);
	if (!match) return null;
	return {
		command: (match[1] || "").trim(),
		cleanedContent: content.replace(COMMAND_MARKER_REGEX, "")
	};
}
function extractResolvedCommandText(content) {
	if (typeof content !== "string" || !content) return null;
	const cancelledMatch = content.match(CANCELLED_COMMAND_TEXT_REGEX);
	if (cancelledMatch?.[1]) {
		const command = cancelledMatch[1].trim();
		if (!command) return null;
		return {
			command,
			status: "cancelled"
		};
	}
	const confirmedMatch = content.match(CONFIRMED_COMMAND_TEXT_REGEX);
	if (confirmedMatch?.[1]) {
		const command = confirmedMatch[1].trim();
		if (!command) return null;
		return {
			command,
			status: "confirmed"
		};
	}
	return null;
}
function extractResolvedCommandMarker(content) {
	if (typeof content !== "string" || !content) return null;
	const match = content.match(RESOLVED_COMMAND_MARKER_REGEX);
	if (!match || !match[1] || !match[2]) return null;
	const status = match[1].trim();
	const command = match[2].trim();
	if (!command) return null;
	return {
		command,
		status,
		cleanedContent: content.replace(RESOLVED_COMMAND_MARKER_REGEX, "")
	};
}
function hasPendingCommandMarker(content) {
	if (typeof content !== "string" || !content) return false;
	return content.includes(COMMAND_MARKERS.CMD_PREFIX) || content.includes(COMMAND_MARKERS.CONFIRM_CMD_PREFIX);
}
function extractOffloadJobMarker(content) {
	return extractMarkerBody(content, OFFLOAD_JOB_MARKER_REGEX);
}
function extractBgJobMarker(content) {
	return extractMarkerBody(content, BG_JOB_MARKER_REGEX);
}
function extractRateLimitMarker(content) {
	if (typeof content !== "string" || !content) return null;
	const match = content.match(RATE_LIMIT_MARKER_REGEX);
	if (!match?.[1]) return null;
	const retryAfterSec = parseInt(match[1], 10);
	if (!Number.isFinite(retryAfterSec) || retryAfterSec <= 0) return null;
	const remainingKeyCountRaw = match[2];
	const remainingKeyCount = remainingKeyCountRaw == null ? null : parseInt(remainingKeyCountRaw, 10);
	if (remainingKeyCountRaw != null && (!Number.isFinite(remainingKeyCount) || remainingKeyCount < 0)) return null;
	return {
		retryAfterSec,
		remainingKeyCount,
		cleanedContent: content.replace(RATE_LIMIT_MARKER_REGEX, "")
	};
}
function hydrateMessageWithCommand(rawMessage) {
	const base = rawMessage && typeof rawMessage === "object" ? { ...rawMessage } : {
		role: "ai",
		content: ""
	};
	const createdAtMs = parseTimestampToMs(base.createdAt);
	const resolvedAtMs = parseTimestampToMs(base.resolvedAt);
	const content = typeof base.content === "string" ? base.content : "";
	if (base.role !== "ai") return {
		...base,
		content
	};
	const marker = extractCommandMarker(content);
	if (marker) return {
		...base,
		content: marker.cleanedContent,
		command: {
			content: marker.command,
			status: "pending",
			...Number.isFinite(createdAtMs) ? { createdAt: createdAtMs } : {}
		}
	};
	const resolvedMarker = extractResolvedCommandMarker(content);
	if (resolvedMarker) return {
		...base,
		content: resolvedMarker.cleanedContent,
		command: {
			content: resolvedMarker.command,
			status: resolvedMarker.status,
			...Number.isFinite(createdAtMs) ? { createdAt: createdAtMs } : {},
			...Number.isFinite(resolvedAtMs) ? { resolvedAt: resolvedAtMs } : {}
		}
	};
	const resolvedCommand = extractResolvedCommandText(content);
	if (!resolvedCommand) return {
		...base,
		content
	};
	return {
		...base,
		content: "",
		command: {
			content: resolvedCommand.command,
			status: resolvedCommand.status,
			...Number.isFinite(createdAtMs) ? { createdAt: createdAtMs } : {},
			...Number.isFinite(resolvedAtMs) ? { resolvedAt: resolvedAtMs } : {}
		}
	};
}

//#endregion
//#region src/stores/chatStore.js
var MODEL_PREFERENCE_STORAGE_KEY = "server-assistant:model-preference";
function readSavedModelPreference() {
	if (typeof window === "undefined" || typeof window.localStorage === "undefined") return "";
	try {
		const saved = window.localStorage.getItem(MODEL_PREFERENCE_STORAGE_KEY);
		return typeof saved === "string" ? saved.trim() : "";
	} catch {
		return "";
	}
}
function persistModelPreference(modelKey) {
	if (typeof window === "undefined" || typeof window.localStorage === "undefined") return;
	try {
		const normalizedModel = typeof modelKey === "string" ? modelKey.trim() : "";
		if (normalizedModel) {
			window.localStorage.setItem(MODEL_PREFERENCE_STORAGE_KEY, normalizedModel);
			return;
		}
		window.localStorage.removeItem(MODEL_PREFERENCE_STORAGE_KEY);
	} catch {}
}
/**
* Chat Store
*
* Manages chat state (messages, input, model selection).
* Streaming logic lives in useChat.js composable.
*/
const useChatStore = defineStore("chat", () => {
	const HISTORY_PAGE_SIZE = Number.isInteger(CHAT_CONFIG.historyPageSize) && CHAT_CONFIG.historyPageSize > 0 ? CHAT_CONFIG.historyPageSize : 50;
	const NEW_CONVERSATION_DRAFT_KEY = "__new_conversation__";
	const messages = ref([]);
	const draftByConversationId = ref({});
	const activeDraftConversationId = ref(NEW_CONVERSATION_DRAFT_KEY);
	const userInput = computed({
		get() {
			return draftByConversationId.value[activeDraftConversationId.value] ?? "";
		},
		set(value) {
			updateDraftForKey(activeDraftConversationId.value, value);
		}
	});
	const isProcessing = ref(false);
	const model = ref(readSavedModelPreference() || DEFAULTS.MODEL);
	const hasMoreHistory = ref(false);
	const isHistoryLoading = ref(false);
	const isLoadingMore = ref(false);
	const totalHistory = ref(0);
	const loadedHistoryCount = ref(0);
	const historyCursorCreatedAt = ref(null);
	const historyCursorId = ref(null);
	const pendingHistoryReloadConversationId = ref("");
	const displayModelName = computed(() => {
		const systemStore = useSystemStore();
		if (systemStore.availableModels[model.value]) return systemStore.availableModels[model.value].label;
		return model.value;
	});
	const hasPendingHistoryReload = computed(() => pendingHistoryReloadConversationId.value.length > 0);
	function clearMessages() {
		messages.value = [];
		hasMoreHistory.value = false;
		isHistoryLoading.value = false;
		isLoadingMore.value = false;
		totalHistory.value = 0;
		loadedHistoryCount.value = 0;
		historyCursorCreatedAt.value = null;
		historyCursorId.value = null;
		pendingHistoryReloadConversationId.value = "";
	}
	/**
	* Load the most recent page of chat history for a conversation.
	* Resets pagination state so "load more" starts from the correct offset.
	* @param {string} conversationId - Conversation ID
	*/
	async function loadHistory(conversationId) {
		if (!conversationId) return false;
		isHistoryLoading.value = true;
		messages.value = [];
		hasMoreHistory.value = false;
		totalHistory.value = 0;
		loadedHistoryCount.value = 0;
		historyCursorCreatedAt.value = null;
		historyCursorId.value = null;
		pendingHistoryReloadConversationId.value = "";
		try {
			const result = await chatApi.getHistory(conversationId, { limit: HISTORY_PAGE_SIZE });
			if (result.success) {
				const { messages: msgs, total, nextCursorCreatedAt, nextCursorId } = result.data ?? {};
				const history = Array.isArray(msgs) ? msgs : [];
				messages.value = history.map(hydrateMessageWithCommand);
				loadedHistoryCount.value = history.length;
				totalHistory.value = typeof total === "number" ? total : 0;
				historyCursorCreatedAt.value = normalizeCursorCreatedAt(nextCursorCreatedAt);
				historyCursorId.value = normalizeCursorId(nextCursorId);
				hasMoreHistory.value = hasRemainingHistory();
				return true;
			}
			return false;
		} catch (error) {
			console.error("Load history error:", error);
			if (shouldRetryHistoryWhenOnline(error)) pendingHistoryReloadConversationId.value = conversationId;
			return false;
		} finally {
			isHistoryLoading.value = false;
		}
	}
	/**
	* Load the next older page of messages and prepend them to the current list.
	* @param {string} conversationId - Conversation ID
	*/
	async function loadMoreHistory(conversationId) {
		if (!conversationId || !hasMoreHistory.value || isLoadingMore.value) return;
		if (!hasValidCursor()) {
			hasMoreHistory.value = false;
			return;
		}
		isLoadingMore.value = true;
		try {
			const result = await chatApi.getHistory(conversationId, {
				limit: HISTORY_PAGE_SIZE,
				beforeCreatedAt: historyCursorCreatedAt.value,
				beforeId: historyCursorId.value
			});
			if (result.success) {
				const { messages: msgs, total, nextCursorCreatedAt, nextCursorId } = result.data ?? {};
				const older = Array.isArray(msgs) ? msgs.map(hydrateMessageWithCommand) : [];
				messages.value = [...older, ...messages.value];
				loadedHistoryCount.value += older.length;
				totalHistory.value = typeof total === "number" ? total : totalHistory.value;
				historyCursorCreatedAt.value = normalizeCursorCreatedAt(nextCursorCreatedAt);
				historyCursorId.value = normalizeCursorId(nextCursorId);
				hasMoreHistory.value = hasRemainingHistory();
			}
		} catch (error) {
			console.error("Load more history error:", error);
		} finally {
			isLoadingMore.value = false;
		}
	}
	async function retryPendingHistoryReload() {
		if (!pendingHistoryReloadConversationId.value) return false;
		if (isHistoryLoading.value) return false;
		return loadHistory(pendingHistoryReloadConversationId.value);
	}
	/**
	* Clear conversation history on server
	* @param {string} conversationId - Conversation ID
	*/
	async function clearHistory(conversationId) {
		if (!conversationId) return;
		try {
			if ((await chatApi.clearHistory(conversationId)).success) {
				clearMessages();
				return true;
			}
			return false;
		} catch (error) {
			console.error("Clear history error:", error);
			return false;
		}
	}
	/**
	* Set AI model
	* @param {string} modelKey - Model key (e.g., '20b', '120b')
	*/
	function setModel(modelKey) {
		model.value = modelKey;
	}
	/**
	* Set current user input draft.
	* @param {string} value - Input text
	*/
	function setUserInput(value) {
		userInput.value = value;
	}
	/**
	* Select which conversation's draft is currently bound to userInput.
	* @param {string} conversationId - Conversation ID
	*/
	function setActiveDraftConversation(conversationId) {
		activeDraftConversationId.value = normalizeDraftConversationKey(conversationId);
	}
	/**
	* Get draft text for a specific conversation.
	* @param {string} conversationId - Conversation ID
	* @returns {string}
	*/
	function getConversationDraft(conversationId) {
		const key = normalizeDraftConversationKey(conversationId);
		return draftByConversationId.value[key] ?? "";
	}
	/**
	* Set draft text for a specific conversation.
	* @param {string} conversationId - Conversation ID
	* @param {string} value - Draft text
	*/
	function setConversationDraft(conversationId, value) {
		updateDraftForKey(normalizeDraftConversationKey(conversationId), value);
	}
	/**
	* Clear draft text for a specific conversation.
	* @param {string} conversationId - Conversation ID
	*/
	function clearConversationDraft(conversationId) {
		setConversationDraft(conversationId, "");
	}
	/**
	* Move draft text from one conversation key to another.
	* Useful when an unsaved conversation is assigned a backend ID.
	* @param {string} sourceConversationId - Source conversation ID
	* @param {string} targetConversationId - Target conversation ID
	*/
	function moveConversationDraft(sourceConversationId, targetConversationId) {
		const sourceKey = normalizeDraftConversationKey(sourceConversationId);
		const targetKey = normalizeDraftConversationKey(targetConversationId);
		if (sourceKey === targetKey) return;
		const sourceDraft = draftByConversationId.value[sourceKey];
		if (typeof sourceDraft !== "string") return;
		const nextDrafts = { ...draftByConversationId.value };
		if (sourceDraft.length > 0) nextDrafts[targetKey] = sourceDraft;
		else delete nextDrafts[targetKey];
		delete nextDrafts[sourceKey];
		draftByConversationId.value = nextDrafts;
	}
	function clearAllDrafts() {
		draftByConversationId.value = {};
		activeDraftConversationId.value = NEW_CONVERSATION_DRAFT_KEY;
	}
	function hasValidCursor() {
		return historyCursorCreatedAt.value !== null && historyCursorId.value !== null;
	}
	function hasRemainingHistory() {
		if (loadedHistoryCount.value >= totalHistory.value) return false;
		return hasValidCursor();
	}
	function shouldRetryHistoryWhenOnline(error) {
		if (typeof navigator !== "undefined" && navigator.onLine === false) return true;
		if (!error || typeof error !== "object") return false;
		if (error.code === "ERR_NETWORK") return true;
		if (error.request && !error.response) return true;
		const normalizedMessage = typeof error.message === "string" ? error.message.toLowerCase() : "";
		return normalizedMessage.includes("network error") || normalizedMessage.includes("failed to fetch");
	}
	function normalizeCursorCreatedAt(value) {
		if (typeof value !== "string") return null;
		const trimmedValue = value.trim();
		return trimmedValue.length > 0 ? trimmedValue : null;
	}
	function normalizeCursorId(value) {
		if (typeof value === "number" && Number.isFinite(value)) return value;
		return null;
	}
	function normalizeDraftConversationKey(conversationId) {
		if (typeof conversationId !== "string") return NEW_CONVERSATION_DRAFT_KEY;
		return conversationId.trim() || NEW_CONVERSATION_DRAFT_KEY;
	}
	function updateDraftForKey(conversationKey, value) {
		const normalizedValue = typeof value === "string" ? value : "";
		const currentValue = draftByConversationId.value[conversationKey];
		if (!normalizedValue) {
			if (typeof currentValue !== "string") return;
			const nextDrafts = { ...draftByConversationId.value };
			delete nextDrafts[conversationKey];
			draftByConversationId.value = nextDrafts;
			return;
		}
		if (currentValue === normalizedValue) return;
		draftByConversationId.value = {
			...draftByConversationId.value,
			[conversationKey]: normalizedValue
		};
	}
	watch(model, (nextModel) => {
		persistModelPreference(nextModel);
	});
	return {
		messages,
		userInput,
		draftByConversationId,
		isProcessing,
		model,
		hasMoreHistory,
		isHistoryLoading,
		isLoadingMore,
		totalHistory,
		pendingHistoryReloadConversationId,
		hasPendingHistoryReload,
		displayModelName,
		clearMessages,
		loadHistory,
		loadMoreHistory,
		retryPendingHistoryReload,
		clearHistory,
		setModel,
		setUserInput,
		setActiveDraftConversation,
		getConversationDraft,
		setConversationDraft,
		clearConversationDraft,
		moveConversationDraft,
		clearAllDrafts
	};
});

//#endregion
//#region src/stores/conversationStore.js
/**
* Conversation Store
*
* Manages conversation list and current conversation selection
*/
const useConversationStore = defineStore("conversation", () => {
	const conversations = ref([]);
	const currentConversationId = ref("");
	const isSidebarOpen = ref(true);
	const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
	function normalizeConversations(result) {
		if (Array.isArray(result)) return result;
		if (result && result.success && Array.isArray(result.data)) return result.data;
		return null;
	}
	/**
	* Load all conversations for current user
	*/
	async function loadConversations(retry = 1) {
		try {
			const parsed = normalizeConversations(await chatApi.getConversations());
			if (parsed) {
				conversations.value = parsed;
				return parsed;
			}
			return conversations.value;
		} catch (error) {
			if (retry > 0) {
				await sleep(250);
				return loadConversations(retry - 1);
			}
			console.error("Load conversations error:", error);
			return conversations.value;
		}
	}
	/**
	* Select a conversation
	* @param {string} conversationId - Conversation ID to select
	*/
	function selectConversation(conversationId) {
		currentConversationId.value = conversationId;
	}
	/**
	* Create new conversation
	*/
	function createNewConversation() {
		currentConversationId.value = "";
	}
	/**
	* Toggle sidebar visibility
	*/
	function toggleSidebar() {
		isSidebarOpen.value = !isSidebarOpen.value;
	}
	/**
	* Open sidebar
	*/
	function openSidebar() {
		isSidebarOpen.value = true;
	}
	/**
	* Close sidebar
	*/
	function closeSidebar() {
		isSidebarOpen.value = false;
	}
	return {
		conversations,
		currentConversationId,
		isSidebarOpen,
		loadConversations,
		selectConversation,
		createNewConversation,
		toggleSidebar,
		openSidebar,
		closeSidebar
	};
});

//#endregion
//#region src/composables/useChat.js
var MAX_RETRIES = 2;
var RETRY_BASE_DELAY_MS = 1500;
var RETRY_JITTER_MAX_MS = 3e3;
function extractErrorCode(payload) {
	if (!payload || typeof payload !== "object") return null;
	if (typeof payload.errorCode === "string" && payload.errorCode.trim()) return payload.errorCode;
	if (payload.error && typeof payload.error === "object" && typeof payload.error.code === "string" && payload.error.code.trim()) return payload.error.code;
	return null;
}
function parseRateLimitReason(value) {
	if (typeof value !== "string") return null;
	const trimmed = value.trim();
	if (trimmed === "user_rate_limit" || trimmed === "user_tpm_limit" || trimmed === "global_tpm_limit") return trimmed;
	return null;
}
function extractRateLimitReason(payload) {
	if (!payload || typeof payload !== "object") return null;
	return parseRateLimitReason(payload.reason) ?? parseRateLimitReason(payload.data?.reason) ?? parseRateLimitReason(payload.details?.reason) ?? parseRateLimitReason(payload.data?.details?.reason) ?? parseRateLimitReason(payload.error?.reason) ?? parseRateLimitReason(payload.error?.details?.reason);
}
function readRetryAfterSecFromPayload(payload) {
	if (!payload || typeof payload !== "object") return null;
	return parsePositiveInt(payload.retryAfterSeconds) ?? parsePositiveInt(payload.details?.retryAfterSeconds) ?? parsePositiveInt(payload.data?.retryAfterSeconds) ?? parsePositiveInt(payload.data?.details?.retryAfterSeconds) ?? parsePositiveInt(payload.error?.retryAfterSeconds) ?? parsePositiveInt(payload.error?.details?.retryAfterSeconds);
}
async function readErrorMetadataFromResponse(response) {
	try {
		const rawBody = await response.text();
		if (!rawBody) return {
			errorCode: null,
			rateLimitReason: null,
			retryAfterSec: null
		};
		const parsed = JSON.parse(rawBody);
		return {
			errorCode: extractErrorCode(parsed),
			rateLimitReason: extractRateLimitReason(parsed),
			retryAfterSec: readRetryAfterSecFromPayload(parsed)
		};
	} catch {
		return {
			errorCode: null,
			rateLimitReason: null,
			retryAfterSec: null
		};
	}
}
function parsePositiveInt(value) {
	const parsed = Number.parseInt(String(value ?? ""), 10);
	return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}
function parseNonNegativeInt(value) {
	const parsed = Number.parseInt(String(value ?? ""), 10);
	return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
}
function readRetryAfterSecFromHeaders(headers) {
	if (!headers) return null;
	if (typeof headers.get === "function") return parsePositiveInt(headers.get("retry-after"));
	if (typeof headers === "object") {
		const direct = headers["retry-after"] ?? headers["Retry-After"];
		if (direct != null) return parsePositiveInt(direct);
		const matchedKey = Object.keys(headers).find((key) => key.toLowerCase() === "retry-after");
		if (matchedKey) return parsePositiveInt(headers[matchedKey]);
	}
	return null;
}
function resolveHttpStatus(error) {
	if (!error || typeof error !== "object") return null;
	return parsePositiveInt(error.httpStatus) ?? parsePositiveInt(error.status) ?? parsePositiveInt(error.response?.status);
}
function hydrateRateLimitMetadata(error, httpStatus) {
	if (!error || typeof error !== "object" || httpStatus !== 429) return;
	const payload = error.response?.data ?? error.data;
	if (!error.errorCode) {
		const errorCode = extractErrorCode(payload);
		if (errorCode) error.errorCode = errorCode;
	}
	if (!error.rateLimitReason) {
		const rateLimitReason = extractRateLimitReason(payload);
		if (rateLimitReason) error.rateLimitReason = rateLimitReason;
	}
	if (parsePositiveInt(error.retryAfterSec) != null) return;
	const retryAfterSecFromBody = readRetryAfterSecFromPayload(payload);
	const retryAfterSec = readRetryAfterSecFromHeaders(error.headers) ?? readRetryAfterSecFromHeaders(error.response?.headers) ?? retryAfterSecFromBody;
	if (retryAfterSec != null) error.retryAfterSec = retryAfterSec;
}
/**
* Classify a fetch/stream error into a typed structure.
* @param {Error} error
* @param {number|null} httpStatus
* @returns {{ type: string, retryable: boolean, delayMs: number, allKeysExhausted: boolean }}
*/
function classifyError(error, httpStatus) {
	if (error instanceof TypeError) return {
		type: "network",
		retryable: true,
		delayMs: RETRY_BASE_DELAY_MS,
		allKeysExhausted: false
	};
	switch (httpStatus) {
		case 401:
		case 403: return {
			type: "auth",
			retryable: false,
			delayMs: 0,
			allKeysExhausted: false
		};
		case 429: {
			const remainingKeyCount = parseNonNegativeInt(error?.remainingKeyCount);
			const rateLimitReason = parseRateLimitReason(error?.rateLimitReason);
			if (error?.errorCode === "CONCURRENT_STREAM_LIMIT_EXCEEDED") return {
				type: "concurrentStream",
				retryable: false,
				delayMs: 0,
				allKeysExhausted: false
			};
			const retryAfterSec = parsePositiveInt(error?.retryAfterSec) ?? 10;
			const jitterMs = Math.random() * RETRY_JITTER_MAX_MS;
			return {
				type: "rateLimit",
				retryable: true,
				delayMs: retryAfterSec * 1e3 + jitterMs,
				allKeysExhausted: remainingKeyCount === 0,
				rateLimitReason
			};
		}
		case 502:
		case 503:
		case 504: return {
			type: "serverUnavailable",
			retryable: true,
			delayMs: RETRY_BASE_DELAY_MS * 2,
			allKeysExhausted: false
		};
		default:
			if (httpStatus != null && httpStatus >= 500) return {
				type: "serverError",
				retryable: false,
				delayMs: 0,
				allKeysExhausted: false
			};
			return {
				type: "unknown",
				retryable: false,
				delayMs: 0,
				allKeysExhausted: false
			};
	}
}
function buildErrorMessage(type, attempt, delaySec = 0, options = {}) {
	const retryNote = attempt > 0 ? `（第 ${attempt} 次重試）` : "";
	switch (type) {
		case "network": return `❌ 網路連線中斷，請確認連線狀態。${retryNote}`;
		case "auth": return "❌ 驗證失敗，請重新登入。";
		case "concurrentStream": return "⚠️ 您已有對話進行中，請等待其完成後再發送。";
		case "rateLimit":
			if (options.rateLimitReason === "user_rate_limit" || options.rateLimitReason === "user_tpm_limit") return delaySec > 0 ? `⏳ 您的請求太頻繁，請稍後 ${delaySec} 秒。${retryNote}` : `⏳ 您的請求太頻繁，請稍後再試。${retryNote}`;
			if (options.rateLimitReason === "global_tpm_limit") return delaySec > 0 ? `⏳ 系統目前繁忙，請稍後 ${delaySec} 秒再試。${retryNote}` : `⏳ 系統目前繁忙，請稍後再試。${retryNote}`;
			if (options.allKeysExhausted) return delaySec > 0 ? `⏳ 目前 AI 服務繁忙，請等待 ${delaySec} 秒後重試，或切換至其他模型。${retryNote}` : `⏳ 目前 AI 服務繁忙，請稍後重試，或切換至其他模型。${retryNote}`;
			return delaySec > 0 ? `⏳ 請等待 ${delaySec} 秒後重試。${retryNote}` : `⏳ 已達 AI 速率上限，請稍後再試。${retryNote}`;
		case "serverUnavailable": return `❌ 伺服器暫時無法回應，請稍後再試。${retryNote}`;
		case "serverError": return "❌ 伺服器發生內部錯誤，請稍後再試。";
		default: return "❌ 伺服器回應異常，請稍後再試。";
	}
}
function buildRetryStatusMessage(classification, remainingSec, attempt) {
	if (classification.type === "rateLimit") {
		if (classification.rateLimitReason === "user_rate_limit" || classification.rateLimitReason === "user_tpm_limit") return `您的請求太頻繁，請稍後 ${remainingSec} 秒`;
		if (classification.rateLimitReason === "global_tpm_limit") return `系統目前繁忙，請稍後 ${remainingSec} 秒再試`;
		if (classification.allKeysExhausted) return `目前 AI 服務繁忙，請等待 ${remainingSec} 秒後重試，或切換至其他模型`;
		return `系統繁忙，將在 ${remainingSec} 秒後自動重試...`;
	}
	return `⏳ 連線重試中，${remainingSec} 秒後進行第 ${attempt} 次重試...`;
}
/**
* Chat Composable
*
* Encapsulates streaming chat logic, command detection, and error handling.
* Extracts the complex streaming implementation from App.vue.
*/
function useChat() {
	const MAX_MESSAGE_LENGTH = CHAT_CONFIG.maxMessageLength || 8e3;
	const chatStore = useChatStore();
	const conversationStore = useConversationStore();
	const systemStore = useSystemStore();
	const { messages, userInput, isProcessing, model } = storeToRefs(chatStore);
	const { currentConversationId } = storeToRefs(conversationStore);
	const { statusMessage } = storeToRefs(systemStore);
	const abortController = ref(null);
	const isRetrying = ref(false);
	const retryCountdown = ref({
		active: false,
		type: "",
		remainingSec: 0,
		totalSec: 0
	});
	let _skipDelayResolve = null;
	function extractExclamationCommand(message) {
		if (typeof message !== "string") return null;
		const trimmed = message.trim();
		if (!trimmed.startsWith("!")) return null;
		return trimmed.slice(1).trim();
	}
	/**
	* Process a single SSE event "data" payload (already reconstructed).
	* @param {string} data - SSE event data payload (may contain newlines)
	* @param {Object} aiMsgObj - AI message object to update
	*/
	function processEventData(data, aiMsgObj) {
		if (data == null) return;
		if (data.trim().startsWith("[STATUS:")) return;
		if (statusMessage.value) statusMessage.value = "";
		aiMsgObj.content += data === "" ? "\n" : data;
		const rateLimitMarker = extractRateLimitMarker(aiMsgObj.content);
		if (rateLimitMarker) {
			aiMsgObj.content = rateLimitMarker.cleanedContent;
			const err = /* @__PURE__ */ new Error("SSE rate limit marker received");
			err.httpStatus = 429;
			err.retryAfterSec = rateLimitMarker.retryAfterSec;
			err.remainingKeyCount = rateLimitMarker.remainingKeyCount;
			throw err;
		}
		const marker = extractCommandMarker(aiMsgObj.content);
		if (marker) {
			aiMsgObj.command = {
				content: marker.command,
				status: "pending",
				createdAt: Date.now()
			};
			aiMsgObj.content = marker.cleanedContent;
		}
	}
	function handleAbortedStream(isRateLimitContext, aiMsgObj) {
		if (isRateLimitContext && (!aiMsgObj.content || !aiMsgObj.content.trim())) aiMsgObj.content = "⏹️ 已取消自動重試。";
		else markAbortedResponse(aiMsgObj);
		resetRetryIndicators();
		return { status: "aborted" };
	}
	function markAbortedResponse(aiMsgObj) {
		aiMsgObj.aborted = true;
		if (typeof aiMsgObj.content !== "string" || !aiMsgObj.content) {
			aiMsgObj.content = "[已中斷回應]";
			return;
		}
		if (!aiMsgObj.content.includes("[已中斷回應]")) aiMsgObj.content = `${aiMsgObj.content}\n[已中斷回應]`;
	}
	/**
	* Handle streaming response from server
	* @param {Response} response - Fetch response object
	* @param {Object} aiMsgObj - AI message object to populate
	* @param {AbortSignal|null} signal - Abort signal for stopping stream read loop
	*/
	async function handleStream(response, aiMsgObj, signal = null) {
		if (!response.ok) {
			const err = /* @__PURE__ */ new Error(`HTTP ${response.status}`);
			err.httpStatus = response.status;
			throw err;
		}
		const contentType = response.headers.get("Content-Type") || "";
		if (contentType.split(";", 1)[0].trim().toLowerCase() !== "text/event-stream") {
			const err = /* @__PURE__ */ new Error(`Unexpected Content-Type: ${contentType || "(empty)"}`);
			err.httpStatus = response.status;
			throw err;
		}
		if (!response.body) {
			const err = /* @__PURE__ */ new Error("Empty response body for SSE stream");
			err.httpStatus = response.status;
			throw err;
		}
		const reader = response.body.getReader();
		const decoder = new TextDecoder();
		let buffer = "";
		let eventDataLines = [];
		const onAbort = () => {
			reader.cancel().catch(() => {});
		};
		try {
			signal?.addEventListener("abort", onAbort, { once: true });
			let isAborted = false;
			while (true) {
				if (signal?.aborted) {
					isAborted = true;
					try {
						await reader.cancel();
					} catch (cancelError) {}
					break;
				}
				const { value, done } = await reader.read();
				if (done) break;
				buffer += decoder.decode(value, { stream: true });
				const lines = buffer.split("\n");
				buffer = lines.pop();
				for (const rawLine of lines) {
					const line = rawLine.replace(/\r$/, "");
					if (line === "") {
						if (eventDataLines.length > 0) {
							processEventData(eventDataLines.join("\n"), aiMsgObj);
							eventDataLines = [];
						}
						continue;
					}
					if (line.startsWith("data:")) eventDataLines.push(line.slice(5).replace(/^ /, ""));
				}
			}
			if (isAborted) throw new DOMException("Aborted", "AbortError");
			if (buffer) {
				const line = buffer.replace(/\r$/, "");
				if (line.startsWith("data:")) eventDataLines.push(line.slice(5).replace(/^ /, ""));
				else if (line === "" && eventDataLines.length > 0) {
					processEventData(eventDataLines.join("\n"), aiMsgObj);
					eventDataLines = [];
				}
			}
			if (eventDataLines.length > 0) {
				processEventData(eventDataLines.join("\n"), aiMsgObj);
				eventDataLines = [];
			}
			const hasCommand = aiMsgObj.command || hasPendingCommandMarker(aiMsgObj.content);
			if ((!aiMsgObj.content || !aiMsgObj.content.trim()) && !hasCommand) aiMsgObj.content = "⚠️ (系統提示：AI 未回傳任何內容，請稍後再試或檢查後端日誌。)";
		} catch (error) {
			try {
				await reader.cancel();
			} catch {}
			throw error;
		} finally {
			signal?.removeEventListener("abort", onAbort);
			reader.releaseLock();
		}
	}
	/**
	* Sleep for `ms` milliseconds, but reject immediately if `signal` is aborted.
	* @param {number} ms
	* @param {AbortSignal|null|undefined} signal
	*/
	function abortableDelay(ms, signal) {
		return new Promise((resolve, reject) => {
			if (signal?.aborted) {
				reject(new DOMException("Aborted", "AbortError"));
				return;
			}
			const id = setTimeout(resolve, ms);
			signal?.addEventListener("abort", () => {
				clearTimeout(id);
				reject(new DOMException("Aborted", "AbortError"));
			}, { once: true });
		});
	}
	function resetRetryIndicators() {
		isRetrying.value = false;
		statusMessage.value = "";
		retryCountdown.value = {
			active: false,
			type: "",
			remainingSec: 0,
			totalSec: 0
		};
		_skipDelayResolve = null;
	}
	/**
	* Send a chat request with automatic retry for transient errors (429, network).
	* Returns `{ status: 'success'|'aborted'|'failed' }`.
	* Non-retryable errors write to aiMsgObj.content.
	*/
	async function sendWithRetry(params, aiMsgObj) {
		resetRetryIndicators();
		let boundedRetryAttempt = 0;
		let lastRetryType = null;
		const signal = abortController.value?.signal ?? null;
		while (true) try {
			const response = await chatApi.streamChat(params, signal);
			if (!response.ok) {
				const retryAfterSecFromHeader = readRetryAfterSecFromHeaders(response.headers);
				const err = /* @__PURE__ */ new Error(`HTTP ${response.status}`);
				err.httpStatus = response.status;
				const { errorCode, rateLimitReason, retryAfterSec } = await readErrorMetadataFromResponse(response);
				if (errorCode) err.errorCode = errorCode;
				if (rateLimitReason) err.rateLimitReason = rateLimitReason;
				if (retryAfterSecFromHeader != null) err.retryAfterSec = retryAfterSecFromHeader;
				else if (retryAfterSec != null) err.retryAfterSec = retryAfterSec;
				throw err;
			}
			await handleStream(response, aiMsgObj, signal);
			return { status: "success" };
		} catch (error) {
			if (error.name === "AbortError") return handleAbortedStream(lastRetryType === "rateLimit", aiMsgObj);
			const resolvedHttpStatus = resolveHttpStatus(error);
			hydrateRateLimitMetadata(error, resolvedHttpStatus);
			const classified = classifyError(error, resolvedHttpStatus);
			if (classified.type === "rateLimit") console.warn("Stream throttled by rate limit; scheduling automatic retry.", error);
			else console.error(`Stream error (attempt ${boundedRetryAttempt}):`, error);
			const isRateLimit = classified.type === "rateLimit";
			const exceededRetryBudget = !isRateLimit && boundedRetryAttempt >= MAX_RETRIES;
			if (!classified.retryable || exceededRetryBudget) {
				const delaySec$1 = classified.delayMs > 0 ? Math.ceil(classified.delayMs / 1e3) : 0;
				aiMsgObj.content = buildErrorMessage(classified.type, boundedRetryAttempt, delaySec$1, {
					allKeysExhausted: classified.allKeysExhausted,
					rateLimitReason: classified.rateLimitReason
				});
				resetRetryIndicators();
				return {
					status: "failed",
					errorType: classified.type
				};
			}
			if (!isRateLimit) boundedRetryAttempt++;
			lastRetryType = classified.type;
			const delaySec = Math.ceil(classified.delayMs / 1e3);
			isRetrying.value = true;
			const skipPromise = new Promise((resolve) => {
				_skipDelayResolve = resolve;
			});
			countdownLoop: for (let remaining = delaySec; remaining > 0; remaining--) {
				statusMessage.value = buildRetryStatusMessage(classified, remaining, boundedRetryAttempt);
				retryCountdown.value = {
					active: true,
					type: classified.type,
					remainingSec: remaining,
					totalSec: delaySec
				};
				try {
					await Promise.race([abortableDelay(1e3, signal), skipPromise]);
				} catch (e) {
					if (e.name === "AbortError") return handleAbortedStream(classified.type === "rateLimit", aiMsgObj);
					throw e;
				}
				if (_skipDelayResolve === null) break countdownLoop;
			}
			resetRetryIndicators();
			aiMsgObj.content = "";
		}
	}
	/**
	* Send message and handle streaming response
	* @param {string|null} content - Message content (uses userInput if null)
	*/
	async function sendMessage(content = null) {
		const useDraftInput = content == null;
		const draftBeforeSend = useDraftInput ? userInput.value : "";
		const msg = typeof content === "string" && content ? content : userInput.value.trim();
		if (!msg || isProcessing.value) return;
		if (msg.length > MAX_MESSAGE_LENGTH) {
			statusMessage.value = `訊息過長（${msg.length} 字元），上限為 ${MAX_MESSAGE_LENGTH} 字元`;
			return;
		}
		const exclamationCommand = extractExclamationCommand(msg);
		if (exclamationCommand !== null) {
			if (!exclamationCommand) {
				statusMessage.value = "請在 ! 後輸入 Linux 指令，例如 !docker ps";
				return;
			}
		}
		isProcessing.value = true;
		statusMessage.value = "";
		let isNewConversation = false;
		const sourceConversationId = currentConversationId.value;
		if (!currentConversationId.value) try {
			const result = await chatApi.createConversation();
			const createdConversationId = typeof result?.data === "string" ? result.data.trim() : "";
			if (!createdConversationId) throw new Error("建立對話失敗：缺少 conversationId");
			chatStore.moveConversationDraft(sourceConversationId, createdConversationId);
			conversationStore.selectConversation(createdConversationId);
			chatStore.setActiveDraftConversation(createdConversationId);
			isNewConversation = true;
		} catch (error) {
			console.error("建立對話失敗:", error);
			isProcessing.value = false;
			statusMessage.value = "無法建立對話，請稍後再試。";
			return;
		}
		messages.value.push({
			role: "user",
			content: msg
		}, {
			role: "ai",
			content: ""
		});
		const aiMsgObj = messages.value[messages.value.length - 1];
		abortController.value = new AbortController();
		try {
			const result = await sendWithRetry({
				message: msg,
				conversationId: currentConversationId.value || null,
				model: model.value
			}, aiMsgObj);
			if (useDraftInput) {
				if (result?.status === "success") {
					if (userInput.value === draftBeforeSend) userInput.value = "";
				} else if (!userInput.value.trim()) userInput.value = draftBeforeSend;
			}
		} finally {
			isProcessing.value = false;
			abortController.value = null;
			statusMessage.value = "";
			if (isNewConversation) try {
				await conversationStore.loadConversations();
			} catch (error) {
				console.error("更新對話列表失敗", error);
			}
		}
	}
	/**
	* Skip the current retry countdown and retry immediately
	*/
	function retryNow() {
		if (_skipDelayResolve) {
			const resolve = _skipDelayResolve;
			_skipDelayResolve = null;
			resolve();
		}
	}
	/**
	* Stop current streaming response
	*/
	function stopStreaming() {
		if (abortController.value) {
			abortController.value.abort();
			abortController.value = null;
		}
	}
	return {
		sendMessage,
		stopStreaming,
		retryNow,
		isRetrying,
		retryCountdown
	};
}

//#endregion
//#region src/composables/useNetworkStatus.js
function useNetworkStatus() {
	const isBackendOnline = ref(true);
	const isBrowserOnline = ref(getInitialBrowserOnline());
	const isOnline = computed(() => isBrowserOnline.value && isBackendOnline.value);
	let pingInterval = null;
	function getInitialBrowserOnline() {
		if (typeof navigator === "undefined") return true;
		return navigator.onLine !== false;
	}
	async function checkBackendOnline() {
		if (!isBrowserOnline.value) {
			isBackendOnline.value = false;
			return;
		}
		try {
			await httpClient_default.get("/ping", { timeout: 5e3 });
			isBackendOnline.value = true;
		} catch {
			isBackendOnline.value = false;
		}
	}
	async function handleNetworkReconnected() {
		isBrowserOnline.value = true;
		await checkBackendOnline();
	}
	function handleNetworkDisconnected() {
		isBrowserOnline.value = false;
		isBackendOnline.value = false;
	}
	function startPing() {
		checkBackendOnline();
		pingInterval = setInterval(checkBackendOnline, 3e4);
	}
	function stopPing() {
		if (pingInterval !== null) {
			clearInterval(pingInterval);
			pingInterval = null;
		}
	}
	return {
		isBackendOnline,
		isBrowserOnline,
		isOnline,
		handleNetworkReconnected,
		handleNetworkDisconnected,
		startPing,
		stopPing
	};
}

//#endregion
//#region src/composables/useSwipeGesture.js
var MOBILE_VIEWPORT_QUERY = "(max-width: 767px)";
var SWIPE_EDGE_TRIGGER_PX = 28;
var SWIPE_OPEN_THRESHOLD_PX = 68;
var SWIPE_CLOSE_THRESHOLD_PX = 52;
var SWIPE_MAX_VERTICAL_DRIFT_PX = 72;
function useSwipeGesture() {
	const conversationStore = useConversationStore();
	const { isSidebarOpen } = storeToRefs(conversationStore);
	const isMobileViewport = ref(false);
	let mobileViewportQuery = null;
	let desktopSidebarStateBeforeMobile = true;
	const swipeState = {
		tracking: false,
		mode: "",
		startX: 0,
		startY: 0
	};
	function isInteractiveTouchTarget(target) {
		return Boolean(target?.closest("button, a, input, textarea, select, [role=\"button\"], [contenteditable=\"true\"], [data-no-swipe]"));
	}
	function resetSwipeTracking() {
		swipeState.tracking = false;
		swipeState.mode = "";
		swipeState.startX = 0;
		swipeState.startY = 0;
	}
	function applyViewportMode(isMobile) {
		const wasMobile = isMobileViewport.value;
		isMobileViewport.value = isMobile;
		if (isMobile === wasMobile) return;
		if (isMobile) {
			desktopSidebarStateBeforeMobile = isSidebarOpen.value;
			conversationStore.closeSidebar();
			return;
		}
		if (desktopSidebarStateBeforeMobile) conversationStore.openSidebar();
		else conversationStore.closeSidebar();
	}
	function handleViewportMediaChange(event) {
		applyViewportMode(Boolean(event.matches));
	}
	function handleLayoutTouchStart(event) {
		resetSwipeTracking();
		if (!isMobileViewport.value || event.touches.length !== 1) return;
		const touch = event.touches[0];
		const target = event.target;
		const fromLeftEdge = touch.clientX <= SWIPE_EDGE_TRIGGER_PX;
		const startedInSidebar = Boolean(target?.closest(".mobile-sidebar-panel"));
		const startedOnSidebarBackdrop = Boolean(target?.closest(".mobile-sidebar-backdrop"));
		if (isInteractiveTouchTarget(target) && !startedOnSidebarBackdrop) return;
		if (!isSidebarOpen.value && fromLeftEdge) swipeState.mode = "open";
		else if (isSidebarOpen.value && (startedInSidebar || startedOnSidebarBackdrop)) swipeState.mode = "close";
		else return;
		swipeState.tracking = true;
		swipeState.startX = touch.clientX;
		swipeState.startY = touch.clientY;
	}
	function handleLayoutTouchMove(event) {
		if (!swipeState.tracking || event.touches.length !== 1) return;
		const touch = event.touches[0];
		const deltaX = touch.clientX - swipeState.startX;
		const deltaY = touch.clientY - swipeState.startY;
		if (Math.abs(deltaY) > SWIPE_MAX_VERTICAL_DRIFT_PX) {
			resetSwipeTracking();
			return;
		}
		if (swipeState.mode === "open" && deltaX >= SWIPE_OPEN_THRESHOLD_PX) {
			conversationStore.openSidebar();
			resetSwipeTracking();
			return;
		}
		if (swipeState.mode === "close" && deltaX <= -SWIPE_CLOSE_THRESHOLD_PX) {
			conversationStore.closeSidebar();
			resetSwipeTracking();
		}
	}
	function handleLayoutTouchEnd() {
		resetSwipeTracking();
	}
	function onSidebarOpenChange(isOpen) {
		if (!isMobileViewport.value) desktopSidebarStateBeforeMobile = isOpen;
	}
	function setupViewportListener() {
		if (typeof window !== "undefined" && typeof window.matchMedia === "function") {
			mobileViewportQuery = window.matchMedia(MOBILE_VIEWPORT_QUERY);
			applyViewportMode(Boolean(mobileViewportQuery.matches));
			if (typeof mobileViewportQuery.addEventListener === "function") mobileViewportQuery.addEventListener("change", handleViewportMediaChange);
			else if (typeof mobileViewportQuery.addListener === "function") mobileViewportQuery.addListener(handleViewportMediaChange);
		}
	}
	function teardownViewportListener() {
		if (mobileViewportQuery) {
			if (typeof mobileViewportQuery.removeEventListener === "function") mobileViewportQuery.removeEventListener("change", handleViewportMediaChange);
			else if (typeof mobileViewportQuery.removeListener === "function") mobileViewportQuery.removeListener(handleViewportMediaChange);
		}
		resetSwipeTracking();
	}
	return {
		isMobileViewport,
		handleLayoutTouchStart,
		handleLayoutTouchMove,
		handleLayoutTouchEnd,
		setupViewportListener,
		teardownViewportListener,
		onSidebarOpenChange
	};
}

//#endregion
//#region src/composables/useKeyboardShortcuts.js
function useKeyboardShortcuts({ sidebarRef, onNewChat } = {}) {
	const authStore = useAuthStore();
	const conversationStore = useConversationStore();
	const systemStore = useSystemStore();
	const { isLoggedIn } = storeToRefs(authStore);
	const { isSidebarOpen } = storeToRefs(conversationStore);
	const { showAdmin } = storeToRefs(systemStore);
	const isShortcutHelpOpen = ref(false);
	async function focusConversationSearch() {
		if (!isLoggedIn.value || showAdmin.value) return;
		conversationStore.openSidebar();
		await nextTick();
		await sidebarRef?.value?.focusSearchInput?.();
	}
	function handleGlobalShortcut(event) {
		if (!isLoggedIn.value || showAdmin.value) return;
		if (event.defaultPrevented || event.isComposing) return;
		if (event.key === "Escape") {
			if (isShortcutHelpOpen.value) {
				event.preventDefault();
				isShortcutHelpOpen.value = false;
				return;
			}
			if (isSidebarOpen.value) {
				event.preventDefault();
				conversationStore.closeSidebar();
			}
			return;
		}
		if (!(event.ctrlKey || event.metaKey) || event.altKey) return;
		const lowerKey = typeof event.key === "string" ? event.key.toLowerCase() : "";
		if (lowerKey === "n" && !event.shiftKey) {
			event.preventDefault();
			onNewChat?.();
			return;
		}
		if (lowerKey === "k" && !event.shiftKey) {
			event.preventDefault();
			focusConversationSearch();
			return;
		}
		if (event.code === "Slash" || lowerKey === "/" || lowerKey === "?") {
			event.preventDefault();
			isShortcutHelpOpen.value = !isShortcutHelpOpen.value;
		}
	}
	return {
		isShortcutHelpOpen,
		handleGlobalShortcut
	};
}

//#endregion
//#region src/composables/useModelSwitchToast.js
var MODEL_SWITCH_TOAST_DURATION_MS = 2200;
function useModelSwitchToast() {
	const showModelSwitchToast = ref(false);
	const modelSwitchToastMessage = ref("");
	let modelSwitchToastTimeoutId = null;
	const suppressNextModelSwitchToast = ref(false);
	function hideModelSwitchToast() {
		if (modelSwitchToastTimeoutId !== null) {
			window.clearTimeout(modelSwitchToastTimeoutId);
			modelSwitchToastTimeoutId = null;
		}
		showModelSwitchToast.value = false;
		modelSwitchToastMessage.value = "";
	}
	function triggerModelSwitchToast(message) {
		if (typeof message !== "string") return;
		const normalizedMessage = message.trim();
		if (!normalizedMessage) return;
		if (modelSwitchToastTimeoutId !== null) {
			window.clearTimeout(modelSwitchToastTimeoutId);
			modelSwitchToastTimeoutId = null;
		}
		modelSwitchToastMessage.value = normalizedMessage;
		showModelSwitchToast.value = true;
		modelSwitchToastTimeoutId = window.setTimeout(() => {
			showModelSwitchToast.value = false;
			modelSwitchToastTimeoutId = null;
		}, MODEL_SWITCH_TOAST_DURATION_MS);
	}
	return {
		showModelSwitchToast,
		modelSwitchToastMessage,
		suppressNextModelSwitchToast,
		hideModelSwitchToast,
		triggerModelSwitchToast
	};
}

//#endregion
//#region src/composables/useCommandConfirmation.js
var COMMAND_CONFIRM_TIMEOUT_MS = COMMAND_CONFIRM_TIMEOUT_SECONDS * 1e3;
var COMMAND_TIMEOUT_MESSAGE = "已逾時，指令已取消";
function useCommandConfirmation() {
	const chatStore = useChatStore();
	const conversationStore = useConversationStore();
	const systemStore = useSystemStore();
	const { messages, isProcessing } = storeToRefs(chatStore);
	const { currentConversationId } = storeToRefs(conversationStore);
	const commandTimeoutHandles = /* @__PURE__ */ new Map();
	let offloadPollToken = 0;
	let commandJobPollToken = 0;
	function sleep(ms) {
		return new Promise((resolve) => setTimeout(resolve, ms));
	}
	function clearCommandTimeout(msg) {
		const timeoutId = commandTimeoutHandles.get(msg);
		if (timeoutId !== void 0) {
			window.clearTimeout(timeoutId);
			commandTimeoutHandles.delete(msg);
		}
	}
	function clearAllCommandTimeouts() {
		for (const timeoutId of commandTimeoutHandles.values()) window.clearTimeout(timeoutId);
		commandTimeoutHandles.clear();
	}
	function resetPollTokens() {
		offloadPollToken++;
		commandJobPollToken++;
	}
	async function autoCancelTimedOutCommand(msg) {
		if (!msg?.command || msg.command.status !== "pending" || msg.command.inFlight) return;
		const timeoutConversationId = msg.command.conversationId ?? null;
		msg.command.status = "cancelled";
		msg.command.resolvedAt = Date.now();
		delete msg.command.timeoutAt;
		delete msg.command.conversationId;
		clearCommandTimeout(msg);
		messages.value.push({
			role: "ai",
			content: COMMAND_TIMEOUT_MESSAGE
		});
		try {
			await httpClient_default.post("/ai/cancel-command", {
				conversationId: timeoutConversationId,
				command: msg.command.content
			});
		} catch (e) {
			console.warn("逾時取消指令失敗:", e);
		}
	}
	function schedulePendingCommandTimeout(msg, { resetDeadline = false } = {}) {
		if (!msg?.command || msg.command.status !== "pending" || msg.command.inFlight) {
			clearCommandTimeout(msg);
			return;
		}
		if (resetDeadline || typeof msg.command.timeoutAt !== "number" || !Number.isFinite(msg.command.timeoutAt)) msg.command.timeoutAt = Date.now() + COMMAND_CONFIRM_TIMEOUT_MS;
		if (!("conversationId" in msg.command)) msg.command.conversationId = currentConversationId.value || null;
		const remainingMs = msg.command.timeoutAt - Date.now();
		if (remainingMs <= 0) {
			autoCancelTimedOutCommand(msg);
			return;
		}
		clearCommandTimeout(msg);
		const timeoutId = window.setTimeout(() => {
			autoCancelTimedOutCommand(msg);
		}, remainingMs);
		commandTimeoutHandles.set(msg, timeoutId);
	}
	function buildOffloadProgressText(progress) {
		if (!progress || typeof progress !== "object") return "Offload 執行中...";
		return `${typeof progress.percent === "number" && progress.percent >= 0 ? `${progress.percent}%` : "--%"}｜${progress.copiedSize || "未知"}`;
	}
	async function pollOffloadProgress(jobId, aiMsg, pollToken) {
		try {
			while (pollToken === offloadPollToken) {
				const progress = (await httpClient_default.get(`/ai/offload-progress/${encodeURIComponent(jobId)}`))?.data?.data;
				if (!progress) throw new Error("無法取得 offload 進度");
				systemStore.setStatusMessage(buildOffloadProgressText(progress));
				if (progress.done) {
					aiMsg.content = progress.result || progress.message || "✅ Offload 完成";
					return;
				}
				await sleep(1e3);
			}
		} catch (e) {
			if (pollToken === offloadPollToken) aiMsg.content = "❌ Offload 進度查詢失敗: " + (e.message || "未知錯誤");
		} finally {
			if (pollToken === offloadPollToken) systemStore.clearStatusMessage();
		}
	}
	async function pollCommandJobProgress(jobId, aiMsg, pollToken) {
		try {
			while (pollToken === commandJobPollToken) {
				const progress = (await httpClient_default.get(`/ai/command-job-progress/${encodeURIComponent(jobId)}`))?.data?.data;
				if (!progress) throw new Error("無法取得背景命令進度");
				systemStore.setStatusMessage(progress.message || "背景命令執行中...");
				if (progress.done) {
					aiMsg.content = progress.result || progress.message || "✅ 背景任務完成";
					return;
				}
				await sleep(1e3);
			}
		} catch (e) {
			if (pollToken === commandJobPollToken) aiMsg.content = "❌ 背景命令進度查詢失敗: " + (e.message || "未知錯誤");
		} finally {
			if (pollToken === commandJobPollToken) systemStore.clearStatusMessage();
		}
	}
	/**
	* Handle command action (confirm/cancel)
	* Confirmation calls the backend directly to bypass AI model unreliability.
	*/
	async function handleCommandAction(msg, action) {
		if (!msg.command || msg.command.status !== "pending") return;
		if (isProcessing.value || msg.command.inFlight) return;
		clearCommandTimeout(msg);
		msg.command.inFlight = true;
		if (action === "confirm") {
			isProcessing.value = true;
			messages.value.push({
				role: "ai",
				content: ""
			});
			const aiMsg = messages.value[messages.value.length - 1];
			try {
				const apiResp = (await httpClient_default.post("/ai/confirm-command", {
					command: msg.command.content,
					conversationId: currentConversationId.value || null
				}))?.data;
				if (apiResp && apiResp.success === false) throw new Error(apiResp.message || "執行失敗");
				const backendMessage = typeof apiResp?.data === "string" && apiResp.data.trim() || typeof apiResp?.message === "string" && apiResp.message.trim() || "✅ 指令已執行完成。";
				const offloadJobId = extractOffloadJobMarker(backendMessage);
				const commandJobId = extractBgJobMarker(backendMessage);
				msg.command.status = "confirmed";
				msg.command.resolvedAt = Date.now();
				delete msg.command.timeoutAt;
				delete msg.command.conversationId;
				if (offloadJobId) {
					const pollToken = ++offloadPollToken;
					aiMsg.content = `⏳ Offload 任務已啟動（Job ID: ${offloadJobId}）`;
					pollOffloadProgress(offloadJobId, aiMsg, pollToken);
				} else if (commandJobId) {
					const pollToken = ++commandJobPollToken;
					aiMsg.content = `⏳ 背景任務已啟動（Job ID: ${commandJobId}）`;
					pollCommandJobProgress(commandJobId, aiMsg, pollToken);
				} else aiMsg.content = backendMessage;
			} catch (e) {
				msg.command.status = "pending";
				delete msg.command.resolvedAt;
				msg.command.timeoutAt = Date.now() + COMMAND_CONFIRM_TIMEOUT_MS;
				aiMsg.content = "❌ 執行失敗: " + (e.message || "未知錯誤");
			} finally {
				isProcessing.value = false;
				msg.command.inFlight = false;
				if (msg.command.status === "pending") schedulePendingCommandTimeout(msg);
			}
		} else {
			isProcessing.value = true;
			try {
				await httpClient_default.post("/ai/cancel-command", {
					conversationId: currentConversationId.value || null,
					command: msg.command.content
				});
				msg.command.status = "cancelled";
				msg.command.resolvedAt = Date.now();
				delete msg.command.timeoutAt;
				delete msg.command.conversationId;
			} catch (e) {
				msg.command.status = "pending";
				delete msg.command.resolvedAt;
				msg.command.timeoutAt = Date.now() + COMMAND_CONFIRM_TIMEOUT_MS;
				console.error("取消指令失敗:", e);
			} finally {
				isProcessing.value = false;
				msg.command.inFlight = false;
				if (msg.command.status === "pending") schedulePendingCommandTimeout(msg);
			}
		}
	}
	return {
		commandTimeoutHandles,
		clearCommandTimeout,
		clearAllCommandTimeouts,
		schedulePendingCommandTimeout,
		handleCommandAction,
		resetPollTokens
	};
}

//#endregion
//#region src/utils/exportUtils.js
function normalizeFilenamePart(rawValue, fallback) {
	return (typeof rawValue === "string" ? rawValue.trim() : "").replace(/[\\/:*?"<>|\u0000-\u001f]/g, "-").replace(/\s+/g, "-").replace(/-+/g, "-").replace(/^-|-$/g, "") || fallback;
}
function sanitizeFilenamePart(value, fallback = "export") {
	return normalizeFilenamePart(value, normalizeFilenamePart(fallback, "export"));
}
function buildExportTimestamp(date = /* @__PURE__ */ new Date()) {
	return date.toISOString().replace(/[:.]/g, "-");
}
function downloadTextFile(filename, content, mimeType = "text/plain;charset=utf-8") {
	if (typeof window === "undefined" || typeof document === "undefined") return;
	const blob = new Blob([content], { type: mimeType });
	const objectUrl = window.URL.createObjectURL(blob);
	const link = document.createElement("a");
	link.href = objectUrl;
	link.download = filename;
	document.body.appendChild(link);
	link.click();
	document.body.removeChild(link);
	window.URL.revokeObjectURL(objectUrl);
}
function escapeCsvCell(value) {
	const normalized = value == null ? "" : String(value);
	if (/[",\n\r]/.test(normalized)) return `"${normalized.replace(/"/g, "\"\"")}"`;
	return normalized;
}
function buildCsvText(headers, rows) {
	return [headers.map((header) => escapeCsvCell(header)).join(","), ...rows.map((row) => row.map((cell) => escapeCsvCell(cell)).join(","))].join("\n");
}

//#endregion
//#region src/composables/useConversationExport.js
var CONVERSATION_EXPORT_PAGE_SIZE = 100;
var MAX_CONVERSATION_EXPORT_PAGES = 200;
function useConversationExport() {
	const { conversations } = storeToRefs(useConversationStore());
	function resolveConversationTitle(conversationId) {
		return conversations.value.find((item) => item.id === conversationId)?.title || "對話紀錄";
	}
	function resolveMessageTimestamp(message) {
		const rawTimestamp = typeof message?.createdAt === "string" ? message.createdAt : typeof message?.timestamp === "string" ? message.timestamp : "";
		if (!rawTimestamp) return "-";
		const parsedDate = new Date(rawTimestamp);
		return Number.isNaN(parsedDate.getTime()) ? rawTimestamp : parsedDate.toLocaleString();
	}
	function renderConversationMarkdown(conversationId, title, messagesForExport) {
		const lines = [
			`# ${title}`,
			"",
			`- Conversation ID: \`${conversationId}\``,
			`- 匯出時間: ${(/* @__PURE__ */ new Date()).toLocaleString()}`,
			`- 訊息數量: ${messagesForExport.length}`,
			"",
			"---",
			""
		];
		messagesForExport.forEach((message, index) => {
			const roleLabel = message.role === "user" ? "User" : "Assistant";
			lines.push(`## ${index + 1}. ${roleLabel} (${resolveMessageTimestamp(message)})`);
			lines.push("");
			if (message.command?.content) {
				const commandStatus = message.command.status || "pending";
				lines.push(`> Command [${commandStatus}]: \`${message.command.content}\``);
				lines.push("");
			}
			const content = typeof message.content === "string" ? message.content.trim() : "";
			lines.push(content || "_[空內容]_");
			lines.push("");
		});
		return `${lines.join("\n").trimEnd()}\n`;
	}
	async function loadConversationHistoryForExport(conversationId) {
		const pages = [];
		let nextCursorCreatedAt = null;
		let nextCursorId = null;
		let reachedPageLimit = true;
		for (let pageIndex = 0; pageIndex < MAX_CONVERSATION_EXPORT_PAGES; pageIndex += 1) {
			const options = { limit: CONVERSATION_EXPORT_PAGE_SIZE };
			if (nextCursorCreatedAt && Number.isFinite(nextCursorId)) {
				options.beforeCreatedAt = nextCursorCreatedAt;
				options.beforeId = nextCursorId;
			}
			const result = await chatApi.getHistory(conversationId, options);
			if (!result?.success) throw new Error(result?.message || "載入對話歷史失敗");
			const payload = result?.data ?? {};
			const pageItems = Array.isArray(payload.messages) ? payload.messages : [];
			if (pageItems.length === 0) {
				reachedPageLimit = false;
				break;
			}
			pages.unshift(pageItems);
			const cursorCreatedAt = typeof payload.nextCursorCreatedAt === "string" ? payload.nextCursorCreatedAt.trim() : "";
			const cursorId = Number(payload.nextCursorId);
			if (!cursorCreatedAt || !Number.isFinite(cursorId)) {
				reachedPageLimit = false;
				break;
			}
			if (cursorCreatedAt === nextCursorCreatedAt && cursorId === nextCursorId) {
				reachedPageLimit = false;
				break;
			}
			nextCursorCreatedAt = cursorCreatedAt;
			nextCursorId = cursorId;
		}
		if (reachedPageLimit && pages.length > 0) throw new Error("對話資料過大，請稍後再試");
		return pages.flat().map((message) => hydrateMessageWithCommand(message));
	}
	async function handleExportChat(payload) {
		const chatId = typeof payload?.chatId === "string" ? payload.chatId : "";
		const exportFormat = payload?.format === "json" ? "json" : "markdown";
		if (!chatId) return;
		try {
			const title = resolveConversationTitle(chatId);
			const exportedMessages = await loadConversationHistoryForExport(chatId);
			const filePrefix = `${sanitizeFilenamePart(title, "conversation")}-${sanitizeFilenamePart(chatId.slice(0, 8), "chat")}-${buildExportTimestamp()}`;
			if (exportFormat === "json") {
				const jsonText = JSON.stringify({
					version: 1,
					exportedAt: (/* @__PURE__ */ new Date()).toISOString(),
					conversationId: chatId,
					title,
					messageCount: exportedMessages.length,
					messages: exportedMessages
				}, null, 2);
				downloadTextFile(`${filePrefix}.json`, jsonText, "application/json;charset=utf-8");
				return;
			}
			const markdownText = renderConversationMarkdown(chatId, title, exportedMessages);
			downloadTextFile(`${filePrefix}.md`, markdownText, "text/markdown;charset=utf-8");
		} catch (error) {
			console.error("匯出對話失敗:", error);
			const message = error?.message || "未知錯誤";
			window.alert(`匯出失敗：${message}`);
		}
	}
	return { handleExportChat };
}

//#endregion
//#region src/composables/useConversationDeletion.js
function useConversationDeletion() {
	const chatStore = useChatStore();
	const conversationStore = useConversationStore();
	const { currentConversationId } = storeToRefs(conversationStore);
	const pendingDelete = ref(null);
	/**
	* Handle conversation deletion — optimistic removal with 5-second undo window.
	*/
	async function handleDeleteChat(id) {
		if (pendingDelete.value) {
			clearTimeout(pendingDelete.value.timeoutId);
			const prevId = pendingDelete.value.id;
			pendingDelete.value = null;
			try {
				await chatApi.clearHistory(prevId);
			} catch (e) {
				console.error("刪除失敗", e);
			}
		}
		const conv = conversationStore.conversations.find((c) => c.id === id);
		if (!conv) return;
		const backupIdx = conversationStore.conversations.findIndex((c) => c.id === id);
		const wasActive = currentConversationId.value === id;
		conversationStore.conversations.splice(backupIdx, 1);
		if (wasActive) {
			chatStore.clearMessages();
			if (conversationStore.conversations.length > 0) {
				const nextId = conversationStore.conversations[0].id;
				conversationStore.selectConversation(nextId);
				chatStore.loadHistory(nextId);
			} else conversationStore.createNewConversation();
		}
		const timeoutId = setTimeout(async () => {
			if (pendingDelete.value?.id === id) {
				pendingDelete.value = null;
				try {
					await chatApi.clearHistory(id);
				} catch (e) {
					console.error("刪除失敗", e);
					await conversationStore.loadConversations();
				}
			}
		}, 5e3);
		pendingDelete.value = {
			id,
			title: conv.title,
			backup: conv,
			backupIdx,
			wasActive,
			timeoutId
		};
	}
	/**
	* Undo a pending conversation deletion within the 5-second window.
	*/
	function handleUndoDelete() {
		if (!pendingDelete.value) return;
		const { timeoutId, backup, backupIdx, wasActive, id } = pendingDelete.value;
		clearTimeout(timeoutId);
		pendingDelete.value = null;
		conversationStore.conversations.splice(backupIdx, 0, backup);
		if (wasActive) {
			conversationStore.selectConversation(id);
			chatStore.clearMessages();
			chatStore.loadHistory(id);
		}
	}
	function clearPendingDelete() {
		if (pendingDelete.value) {
			clearTimeout(pendingDelete.value.timeoutId);
			pendingDelete.value = null;
		}
	}
	return {
		pendingDelete,
		handleDeleteChat,
		handleUndoDelete,
		clearPendingDelete
	};
}

//#endregion
//#region src/composables/useMessageEditing.js
var DELETE_LAST_MESSAGES_BATCH_SIZE = 20;
function useMessageEditing({ sendMessage } = {}) {
	const chatStore = useChatStore();
	const conversationStore = useConversationStore();
	const systemStore = useSystemStore();
	const { messages, isProcessing } = storeToRefs(chatStore);
	const { currentConversationId } = storeToRefs(conversationStore);
	/**
	* Handle "load more" — fetches the next older page and prepends to messages.
	*/
	async function handleLoadMore() {
		if (currentConversationId.value) await chatStore.loadMoreHistory(currentConversationId.value);
	}
	/**
	* Find the closest user message index before a given index.
	*/
	function findPreviousUserMessageIndex(fromIndex) {
		const startIndex = Math.min(fromIndex - 1, messages.value.length - 1);
		for (let idx = startIndex; idx >= 0; idx--) if (messages.value[idx]?.role === "user") return idx;
		return -1;
	}
	function canRegenerateMessage(messageIndex) {
		if (isProcessing.value) return false;
		const msg = messages.value[messageIndex];
		if (!msg || msg.role !== "ai") return false;
		if (msg.command && (msg.command.status === "pending" || msg.command.status === "cancelled")) return false;
		if (msg.content.includes("已取消指令") || msg.content.includes("已執行指令")) return false;
		return findPreviousUserMessageIndex(messageIndex) >= 0;
	}
	async function deleteConversationTailFromServer(messagesToDelete) {
		if (messagesToDelete <= 0) return true;
		if (!currentConversationId.value) return true;
		let remaining = messagesToDelete;
		while (remaining > 0) {
			const batchSize = Math.min(remaining, DELETE_LAST_MESSAGES_BATCH_SIZE);
			try {
				await chatApi.deleteLastMessages(currentConversationId.value, batchSize);
				remaining -= batchSize;
			} catch (e) {
				console.warn("deleteLastMessages failed (non-fatal):", e);
				systemStore.setStatusMessage("無法同步更新對話紀錄，請重新整理後再試。");
				return false;
			}
		}
		return true;
	}
	async function trimMessagesFromIndex(startIndex) {
		if (!Number.isInteger(startIndex) || startIndex < 0 || startIndex >= messages.value.length) return false;
		const messagesToDelete = messages.value.length - startIndex;
		if (!await deleteConversationTailFromServer(messagesToDelete)) return false;
		messages.value.splice(startIndex, messagesToDelete);
		systemStore.clearStatusMessage();
		return true;
	}
	/**
	* Edit a sent user message: trim trailing history and put content back to input box.
	*/
	async function handleEditMessage(messageIndex) {
		if (isProcessing.value) return;
		const msg = messages.value[messageIndex];
		if (!msg || msg.role !== "user") return;
		if (!await trimMessagesFromIndex(messageIndex)) return;
		chatStore.setUserInput(msg.content || "");
	}
	/**
	* Regenerate from a specific AI response by removing it and its source prompt, then resending.
	*/
	async function handleRegenerateMessage(messageIndex) {
		if (!canRegenerateMessage(messageIndex)) return;
		const userMessageIndex = findPreviousUserMessageIndex(messageIndex);
		if (userMessageIndex < 0) return;
		const text = messages.value[userMessageIndex]?.content || "";
		if (!text.trim()) return;
		if (!await trimMessagesFromIndex(userMessageIndex)) return;
		await sendMessage(text);
	}
	return {
		handleLoadMore,
		canRegenerateMessage,
		handleEditMessage,
		handleRegenerateMessage
	};
}

//#endregion
//#region src/utils/modelSwitch.js
function normalizeModelKey(value) {
	if (typeof value !== "string") return "";
	return value.trim();
}
function isModelAvailable(config) {
	return Boolean(config) && config.available !== false;
}
function findFirstAvailableModelKey(models, excludeKey = "") {
	for (const [key, config] of Object.entries(models || {})) {
		if (key === excludeKey) continue;
		if (isModelAvailable(config)) return key;
	}
	return "";
}
function resolveModelLabel(models, modelKey) {
	const normalizedKey = normalizeModelKey(modelKey);
	if (!normalizedKey) return "";
	const config = models?.[normalizedKey];
	if (!config || typeof config !== "object") return normalizedKey;
	return (typeof config.label === "string" ? config.label.trim() : "") || normalizedKey;
}
/**
* Resolve automatic model-switch strategy for unavailable/missing models.
* Returns null when no auto-switch should happen.
*/
function resolveModelAutoSwitchPlan(currentModelKey, models) {
	const modelMap = models && typeof models === "object" ? models : {};
	const modelKeys = Object.keys(modelMap);
	if (modelKeys.length === 0) return null;
	const normalizedCurrentKey = normalizeModelKey(currentModelKey);
	const currentConfig = normalizedCurrentKey ? modelMap[normalizedCurrentKey] : null;
	if (!currentConfig) {
		const targetModelKey = findFirstAvailableModelKey(modelMap) || modelKeys[0];
		if (!targetModelKey || targetModelKey === normalizedCurrentKey) return null;
		return {
			reason: "missing",
			currentModelKey: normalizedCurrentKey,
			targetModelKey
		};
	}
	if (currentConfig.available !== false) return null;
	const suggestedKey = normalizeModelKey(currentConfig.suggestAlternative);
	if (suggestedKey && suggestedKey !== normalizedCurrentKey && isModelAvailable(modelMap[suggestedKey])) return {
		reason: "suggested",
		currentModelKey: normalizedCurrentKey,
		targetModelKey: suggestedKey
	};
	const fallbackModelKey = findFirstAvailableModelKey(modelMap, normalizedCurrentKey);
	if (fallbackModelKey) return {
		reason: "fallback",
		currentModelKey: normalizedCurrentKey,
		targetModelKey: fallbackModelKey
	};
	return null;
}

//#endregion
//#region \0vite/preload-helper.js
var scriptRel = "modulepreload";
var assetsURL = function(dep) {
	return "/" + dep;
};
var seen = {};
const __vitePreload = function preload(baseModule, deps, importerUrl) {
	let promise = Promise.resolve();
	if (true               && deps && deps.length > 0) {
		const links = document.getElementsByTagName("link");
		const cspNonceMeta = document.querySelector("meta[property=csp-nonce]");
		const cspNonce = cspNonceMeta?.nonce || cspNonceMeta?.getAttribute("nonce");
		function allSettled(promises$2) {
			return Promise.all(promises$2.map((p) => Promise.resolve(p).then((value$1) => ({
				status: "fulfilled",
				value: value$1
			}), (reason) => ({
				status: "rejected",
				reason
			}))));
		}
		promise = allSettled(deps.map((dep) => {
			dep = assetsURL(dep, importerUrl);
			if (dep in seen) return;
			seen[dep] = true;
			const isCss = dep.endsWith(".css");
			const cssSelector = isCss ? "[rel=\"stylesheet\"]" : "";
			if (!!importerUrl) for (let i$1 = links.length - 1; i$1 >= 0; i$1--) {
				const link$1 = links[i$1];
				if (link$1.href === dep && (!isCss || link$1.rel === "stylesheet")) return;
			}
			else if (document.querySelector(`link[href="${dep}"]${cssSelector}`)) return;
			const link = document.createElement("link");
			link.rel = isCss ? "stylesheet" : scriptRel;
			if (!isCss) link.as = "script";
			link.crossOrigin = "";
			link.href = dep;
			if (cspNonce) link.setAttribute("nonce", cspNonce);
			document.head.appendChild(link);
			if (isCss) return new Promise((res, rej) => {
				link.addEventListener("load", res);
				link.addEventListener("error", () => rej(/* @__PURE__ */ new Error(`Unable to preload CSS for ${dep}`)));
			});
		}));
	}
	function handlePreloadError(err$2) {
		const e$1 = new Event("vite:preloadError", { cancelable: true });
		e$1.payload = err$2;
		window.dispatchEvent(e$1);
		if (!e$1.defaultPrevented) throw err$2;
	}
	return promise.then((res) => {
		for (const item of res || []) {
			if (item.status !== "rejected") continue;
			handlePreloadError(item.reason);
		}
		return baseModule().catch(handlePreloadError);
	});
};

//#endregion
//#region src/App.vue
var _hoisted_1 = {
	class: "h-screen flex flex-col",
	style: { "background-color": "var(--bg-primary)" }
};
var _hoisted_2 = {
	key: 0,
	class: "flex-1 flex items-center justify-center",
	style: { "background-color": "var(--bg-primary)" }
};
var _hoisted_3 = { class: "flex flex-col items-center gap-4" };
var _hoisted_4 = {
	class: "animate-spin h-8 w-8",
	xmlns: "http://www.w3.org/2000/svg",
	fill: "none",
	viewBox: "0 0 24 24",
	style: { "color": "var(--accent-primary)" }
};
var _sfc_main = {
	__name: "App",
	setup(__props) {
		const AdminDashboard = defineAsyncComponent(() => __vitePreload(() => import("./AdminDashboard-DseJ2ei4.js"), __vite__mapDeps([0,1,2,3,4,5])));
		const authStore = useAuthStore();
		const chatStore = useChatStore();
		const conversationStore = useConversationStore();
		const systemStore = useSystemStore();
		const { isLoggedIn, currentUser, isAdmin } = storeToRefs(authStore);
		const { messages, userInput, isProcessing, model, displayModelName, hasMoreHistory, isHistoryLoading, isLoadingMore, hasPendingHistoryReload } = storeToRefs(chatStore);
		const { conversations, currentConversationId, isSidebarOpen } = storeToRefs(conversationStore);
		const { serverIp, availableModels, showAdmin, statusMessage } = storeToRefs(systemStore);
		const isInitializing = ref(true);
		const chatInterfaceRef = ref(null);
		let bootstrapPromise = null;
		const { sendMessage, stopStreaming, retryNow, isRetrying, retryCountdown } = useChat();
		const { isBackendOnline, isBrowserOnline, isOnline, handleNetworkReconnected, handleNetworkDisconnected, startPing, stopPing } = useNetworkStatus();
		const { isMobileViewport, handleLayoutTouchStart, handleLayoutTouchMove, handleLayoutTouchEnd, setupViewportListener, teardownViewportListener, onSidebarOpenChange } = useSwipeGesture();
		const { showModelSwitchToast, modelSwitchToastMessage, suppressNextModelSwitchToast, hideModelSwitchToast, triggerModelSwitchToast } = useModelSwitchToast();
		const { isShortcutHelpOpen, handleGlobalShortcut } = useKeyboardShortcuts({
			sidebarRef: chatInterfaceRef,
			onNewChat: handleNewChat
		});
		const { commandTimeoutHandles, clearCommandTimeout, clearAllCommandTimeouts, schedulePendingCommandTimeout, handleCommandAction, resetPollTokens } = useCommandConfirmation();
		const { handleExportChat } = useConversationExport();
		const { pendingDelete, handleDeleteChat, handleUndoDelete, clearPendingDelete } = useConversationDeletion();
		const { handleLoadMore, canRegenerateMessage, handleEditMessage, handleRegenerateMessage } = useMessageEditing({ sendMessage });
		function resetWorkspaceState() {
			resetPollTokens();
			clearAllCommandTimeouts();
			hideModelSwitchToast();
			clearPendingDelete();
			systemStore.clearStatusMessage();
			chatStore.clearMessages();
			chatStore.clearAllDrafts();
			conversationStore.conversations = [];
			conversationStore.currentConversationId = "";
			isShortcutHelpOpen.value = false;
			systemStore.closeAdmin();
		}
		async function initializeWorkspace() {
			if (!isLoggedIn.value) return;
			if (bootstrapPromise) return bootstrapPromise;
			bootstrapPromise = (async () => {
				isHistoryLoading.value = true;
				await Promise.all([systemStore.initialize(), conversationStore.loadConversations()]);
				if (conversations.value.length === 0) {
					chatStore.clearMessages();
					conversationStore.createNewConversation();
					return;
				}
				const targetId = currentConversationId.value && conversations.value.some((c) => c.id === currentConversationId.value) ? currentConversationId.value : conversations.value[0].id;
				if (targetId) {
					conversationStore.selectConversation(targetId);
					await chatStore.loadHistory(targetId);
				} else isHistoryLoading.value = false;
			})().finally(() => {
				bootstrapPromise = null;
			});
			return bootstrapPromise;
		}
		/**
		* Handle successful login
		*/
		async function handleLoginSuccess() {
			if (!isLoggedIn.value) {
				if (!await authStore.checkStatus()) return;
			}
			await initializeWorkspace();
		}
		/**
		* Handle logout
		*/
		async function handleLogout() {
			await authStore.logout();
		}
		/**
		* Handle new chat creation
		*/
		function handleNewChat() {
			conversationStore.createNewConversation();
			chatStore.clearMessages();
			chatStore.setUserInput("");
			if (isMobileViewport.value) conversationStore.closeSidebar();
		}
		/**
		* Handle conversation selection
		*/
		async function handleSelectChat(id) {
			if (currentConversationId.value === id) return;
			conversationStore.selectConversation(id);
			if (isMobileViewport.value) conversationStore.closeSidebar();
			await chatStore.loadHistory(id);
		}
		async function syncWorkspaceConversations() {
			await conversationStore.loadConversations();
			if (conversations.value.length === 0) {
				chatStore.clearMessages();
				conversationStore.createNewConversation();
				return;
			}
			if (!(currentConversationId.value && conversations.value.some((c) => c.id === currentConversationId.value))) {
				const targetId = conversations.value[0].id;
				conversationStore.selectConversation(targetId);
				await chatStore.loadHistory(targetId);
			}
		}
		async function handleAdminConversationsUpdated() {
			await syncWorkspaceConversations();
		}
		/**
		* Handle admin dashboard close
		*/
		async function closeAdmin() {
			systemStore.closeAdmin();
			await Promise.all([systemStore.loadModels(), syncWorkspaceConversations()]);
			if (!availableModels.value[model.value] && Object.keys(availableModels.value).length > 0) chatStore.setModel(Object.keys(availableModels.value)[0]);
		}
		onMounted(async () => {
			setupViewportListener();
			window.addEventListener("online", handleNetworkReconnected);
			window.addEventListener("offline", handleNetworkDisconnected);
			window.addEventListener("keydown", handleGlobalShortcut);
			try {
				if (await authStore.checkStatus()) await initializeWorkspace();
			} finally {
				isInitializing.value = false;
			}
			startPing();
		});
		watch(isLoggedIn, async (loggedIn, previousLoggedIn) => {
			if (!loggedIn && previousLoggedIn) {
				resetWorkspaceState();
				return;
			}
			if (loggedIn && conversations.value.length === 0) await initializeWorkspace();
		});
		watch(isBackendOnline, async (online, previousOnline) => {
			if (!online || previousOnline) return;
			if (!isLoggedIn.value || !hasPendingHistoryReload.value) return;
			await chatStore.retryPendingHistoryReload();
		});
		watch(isSidebarOpen, (isOpen) => {
			onSidebarOpenChange(isOpen);
		});
		watch(availableModels, (models) => {
			const plan = resolveModelAutoSwitchPlan(model.value, models);
			if (!plan) return;
			suppressNextModelSwitchToast.value = true;
			chatStore.setModel(plan.targetModelKey);
			const targetLabel = resolveModelLabel(models, plan.targetModelKey);
			if (plan.reason === "missing") {
				triggerModelSwitchToast(`模型「${plan.currentModelKey ? resolveModelLabel(models, plan.currentModelKey) : "先前模型"}」不可用，已自動切換至 ${targetLabel}`);
				return;
			}
			triggerModelSwitchToast(`模型「${resolveModelLabel(models, plan.currentModelKey)}」目前負載高，已自動切換至 ${targetLabel}`);
		}, { immediate: true });
		watch(currentConversationId, (conversationId) => {
			chatStore.setActiveDraftConversation(conversationId);
		}, { immediate: true });
		watch(model, (nextModel, previousModel) => {
			if (!nextModel || nextModel === previousModel) return;
			if (suppressNextModelSwitchToast.value) {
				suppressNextModelSwitchToast.value = false;
				return;
			}
			triggerModelSwitchToast(`已切換至 ${resolveModelLabel(availableModels.value, nextModel)}`);
		});
		watch(() => messages.value.map((msg) => ({
			msg,
			status: msg.command?.status,
			inFlight: Boolean(msg.command?.inFlight),
			timeoutAt: msg.command?.timeoutAt
		})), () => {
			const activeMessages = new Set(messages.value);
			for (const msg of messages.value) {
				if (!msg.command || msg.command.status !== "pending" || msg.command.inFlight) {
					clearCommandTimeout(msg);
					continue;
				}
				schedulePendingCommandTimeout(msg);
			}
			for (const trackedMsg of commandTimeoutHandles.keys()) if (!activeMessages.has(trackedMsg)) clearCommandTimeout(trackedMsg);
		}, { immediate: true });
		onBeforeUnmount(() => {
			teardownViewportListener();
			window.removeEventListener("online", handleNetworkReconnected);
			window.removeEventListener("offline", handleNetworkDisconnected);
			window.removeEventListener("keydown", handleGlobalShortcut);
			stopPing();
			resetWorkspaceState();
		});
		return (_ctx, _cache) => {
			return openBlock(), createElementBlock("div", _hoisted_1, [
				createVNode(ModelSwitchToast_default, {
					show: unref(showModelSwitchToast),
					message: unref(modelSwitchToastMessage)
				}, null, 8, ["show", "message"]),
				createVNode(NetworkOfflineBanner_default, { show: unref(isLoggedIn) && !unref(isBrowserOnline) }, null, 8, ["show"]),
				createVNode(ShortcutHelpDialog_default, {
					isOpen: unref(isShortcutHelpOpen) && unref(isLoggedIn) && !unref(showAdmin),
					onClose: _cache[0] || (_cache[0] = ($event) => isShortcutHelpOpen.value = false)
				}, null, 8, ["isOpen"]),
				createVNode(UndoDeleteToast_default, {
					pendingDelete: unref(pendingDelete),
					onUndo: unref(handleUndoDelete)
				}, null, 8, ["pendingDelete", "onUndo"]),
				isInitializing.value ? (openBlock(), createElementBlock("div", _hoisted_2, [createBaseVNode("div", _hoisted_3, [(openBlock(), createElementBlock("svg", _hoisted_4, [..._cache[7] || (_cache[7] = [createBaseVNode("circle", {
					class: "opacity-25",
					cx: "12",
					cy: "12",
					r: "10",
					stroke: "currentColor",
					"stroke-width": "4"
				}, null, -1), createBaseVNode("path", {
					class: "opacity-75",
					fill: "currentColor",
					d: "M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
				}, null, -1)])])), _cache[8] || (_cache[8] = createBaseVNode("span", {
					class: "text-sm",
					style: { "color": "var(--text-secondary)" }
				}, "載入中…", -1))])])) : !unref(isLoggedIn) ? (openBlock(), createBlock(Login_default, {
					key: 1,
					onLoginSuccess: handleLoginSuccess
				})) : unref(isLoggedIn) && unref(showAdmin) ? (openBlock(), createBlock(unref(AdminDashboard), {
					key: 2,
					onClose: closeAdmin,
					onConversationsUpdated: handleAdminConversationsUpdated
				})) : unref(isLoggedIn) ? (openBlock(), createBlock(ChatInterfaceLayout_default, {
					key: 3,
					ref_key: "chatInterfaceRef",
					ref: chatInterfaceRef,
					isMobileViewport: unref(isMobileViewport),
					isSidebarOpen: unref(isSidebarOpen),
					conversations: unref(conversations),
					currentConversationId: unref(currentConversationId),
					serverIp: unref(serverIp),
					isOnline: unref(isOnline),
					displayModelName: unref(displayModelName),
					currentUser: unref(currentUser),
					isAdmin: unref(isAdmin),
					messages: unref(messages),
					isProcessing: unref(isProcessing),
					userInput: unref(userInput),
					model: unref(model),
					availableModels: unref(availableModels),
					statusMessage: unref(statusMessage),
					isRetrying: unref(isRetrying),
					retryCountdown: unref(retryCountdown),
					onRetry: unref(retryNow),
					onCancelRetry: unref(stopStreaming),
					canRegenerateMessage: unref(canRegenerateMessage),
					hasMoreFromServer: unref(hasMoreHistory),
					isHistoryLoading: unref(isHistoryLoading),
					isLoadingMore: unref(isLoadingMore),
					onTouchstart: unref(handleLayoutTouchStart),
					onTouchmove: unref(handleLayoutTouchMove),
					onTouchend: unref(handleLayoutTouchEnd),
					onTouchcancel: unref(handleLayoutTouchEnd),
					onCloseSidebar: _cache[1] || (_cache[1] = ($event) => unref(conversationStore).closeSidebar()),
					onNewChat: handleNewChat,
					onSelectChat: handleSelectChat,
					onDeleteChat: unref(handleDeleteChat),
					onExportChat: unref(handleExportChat),
					onToggleSidebar: _cache[2] || (_cache[2] = ($event) => unref(conversationStore).toggleSidebar()),
					onOpenAdmin: _cache[3] || (_cache[3] = ($event) => unref(systemStore).openAdmin()),
					onLogout: handleLogout,
					onCommandAction: _cache[4] || (_cache[4] = ({ msg, action }) => unref(handleCommandAction)(msg, action)),
					"onUpdate:userInput": _cache[5] || (_cache[5] = ($event) => unref(chatStore).setUserInput($event)),
					"onUpdate:model": _cache[6] || (_cache[6] = ($event) => unref(chatStore).setModel($event)),
					onSend: unref(sendMessage),
					onStop: unref(stopStreaming),
					onEditMessage: unref(handleEditMessage),
					onRegenerateMessage: unref(handleRegenerateMessage),
					onLoadMore: unref(handleLoadMore)
				}, null, 8, [
					"isMobileViewport",
					"isSidebarOpen",
					"conversations",
					"currentConversationId",
					"serverIp",
					"isOnline",
					"displayModelName",
					"currentUser",
					"isAdmin",
					"messages",
					"isProcessing",
					"userInput",
					"model",
					"availableModels",
					"statusMessage",
					"isRetrying",
					"retryCountdown",
					"onRetry",
					"onCancelRetry",
					"canRegenerateMessage",
					"hasMoreFromServer",
					"isHistoryLoading",
					"isLoadingMore",
					"onTouchstart",
					"onTouchmove",
					"onTouchend",
					"onTouchcancel",
					"onDeleteChat",
					"onExportChat",
					"onSend",
					"onStop",
					"onEditMessage",
					"onRegenerateMessage",
					"onLoadMore"
				])) : createCommentVNode("", true)
			]);
		};
	}
};
var App_default = _sfc_main;

//#endregion
//#region src/main.js
var app = createApp(App_default);
var pinia = createPinia();
app.use(pinia);
useThemeStore().initTheme();
app.mount("#app");

//#endregion
export { __plugin_vue_export_helper_default as a, sanitizeFilenamePart as i, buildExportTimestamp as n, httpClient_default as o, downloadTextFile as r, useThemeStore as s, buildCsvText as t };