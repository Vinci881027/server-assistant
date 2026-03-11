import { D as openBlock, E as onUnmounted, F as ref, I as unref, L as normalizeClass, M as watch, O as renderList, P as withDirectives, R as normalizeStyle, T as onMounted, _ as createElementBlock, f as Fragment, g as createCommentVNode, l as vModelText, m as createBaseVNode, o as vModelCheckbox, p as computed, y as createTextVNode, z as toDisplayString } from "./vendor-vue-DigYjX59.js";
import { t as lib_default } from "./vendor-utils-BU-w9I5u.js";
import { t as purify } from "./vendor-CmHsBc4_.js";
import { a as __plugin_vue_export_helper_default, i as sanitizeFilenamePart, n as buildExportTimestamp, o as httpClient_default, r as downloadTextFile, s as useThemeStore, t as buildCsvText } from "./index-BNj87f6r.js";

//#region src/components/AdminDashboard.vue
var _hoisted_1 = {
	class: "fixed inset-0 z-50 flex flex-col font-sans",
	style: {
		"background-color": "var(--bg-primary)",
		"color": "var(--text-primary)"
	}
};
var _hoisted_2 = {
	class: "flex items-center justify-between px-6 py-4 border-b",
	style: {
		"background-color": "var(--bg-secondary)",
		"border-color": "var(--border-primary)"
	}
};
var _hoisted_3 = { class: "flex items-center gap-2" };
var _hoisted_4 = {
	key: 0,
	xmlns: "http://www.w3.org/2000/svg",
	class: "h-4 w-4",
	fill: "none",
	viewBox: "0 0 24 24",
	stroke: "currentColor",
	style: { "color": "var(--accent-warning)" }
};
var _hoisted_5 = {
	key: 1,
	xmlns: "http://www.w3.org/2000/svg",
	class: "h-4 w-4",
	fill: "none",
	viewBox: "0 0 24 24",
	stroke: "currentColor",
	style: { "color": "var(--accent-primary)" }
};
var _hoisted_6 = { class: "flex flex-1 overflow-hidden" };
var _hoisted_7 = {
	class: "w-60 border-r flex flex-col",
	style: {
		"background-color": "var(--bg-secondary)",
		"border-color": "var(--border-primary)"
	}
};
var _hoisted_8 = { class: "flex-1 overflow-y-auto p-2 space-y-0.5 custom-scrollbar" };
var _hoisted_9 = [
	"onClick",
	"onMouseenter",
	"onMouseleave"
];
var _hoisted_10 = {
	class: "p-3 border-t space-y-1.5",
	style: { "border-color": "var(--border-primary)" }
};
var _hoisted_11 = ["disabled"];
var _hoisted_12 = ["disabled"];
var _hoisted_13 = {
	key: 0,
	class: "text-[10px] leading-snug px-1",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_14 = {
	class: "flex-1 flex flex-col overflow-hidden relative",
	style: { "background-color": "var(--bg-primary)" }
};
var _hoisted_15 = {
	class: "flex border-b sticky top-0 z-10",
	style: {
		"background-color": "var(--bg-secondary)",
		"border-color": "var(--border-primary)"
	}
};
var _hoisted_16 = {
	key: 0,
	class: "flex-1 overflow-y-auto p-8 custom-scrollbar"
};
var _hoisted_17 = {
	class: "mb-6 p-5 rounded-xl border",
	style: {
		"background-color": "var(--bg-secondary)",
		"border-color": "var(--border-primary)"
	}
};
var _hoisted_18 = { class: "flex items-center justify-between gap-4 mb-4" };
var _hoisted_19 = ["disabled"];
var _hoisted_20 = { class: "grid grid-cols-1 md:grid-cols-2 gap-3" };
var _hoisted_21 = {
	class: "text-xs",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_22 = {
	class: "text-xs",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_23 = {
	class: "text-xs md:col-span-2",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_24 = {
	class: "text-xs",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_25 = {
	class: "text-xs",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_26 = {
	class: "text-xs flex items-center gap-2",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_27 = {
	key: 0,
	class: "mt-3 text-[11px]",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_28 = { class: "grid gap-3" };
var _hoisted_29 = { class: "flex items-start justify-between gap-4" };
var _hoisted_30 = { class: "min-w-0" };
var _hoisted_31 = {
	class: "font-mono text-sm truncate",
	style: { "color": "var(--text-primary)" }
};
var _hoisted_32 = { class: "flex items-center gap-2 flex-shrink-0" };
var _hoisted_33 = {
	class: "text-xs flex items-center gap-1.5",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_34 = ["onUpdate:modelValue"];
var _hoisted_35 = ["onClick", "disabled"];
var _hoisted_36 = ["onClick", "disabled"];
var _hoisted_37 = { class: "mt-4 grid grid-cols-1 md:grid-cols-2 gap-3" };
var _hoisted_38 = {
	class: "text-xs",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_39 = ["onUpdate:modelValue"];
var _hoisted_40 = {
	class: "text-xs",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_41 = ["onUpdate:modelValue"];
var _hoisted_42 = {
	class: "text-xs md:col-span-2",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_43 = ["onUpdate:modelValue"];
var _hoisted_44 = {
	class: "text-xs",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_45 = ["onUpdate:modelValue"];
var _hoisted_46 = {
	key: 1,
	class: "flex-1 overflow-y-auto p-6 custom-scrollbar"
};
var _hoisted_47 = {
	key: 0,
	class: "mb-4 flex flex-wrap items-center gap-2"
};
var _hoisted_48 = {
	class: "text-xs font-mono px-2 py-1 rounded-lg border",
	style: {
		"background-color": "var(--bg-secondary)",
		"border-color": "var(--border-primary)",
		"color": "var(--text-primary)"
	}
};
var _hoisted_49 = {
	class: "text-[10px]",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_50 = ["disabled"];
var _hoisted_51 = ["disabled"];
var _hoisted_52 = ["disabled"];
var _hoisted_53 = ["disabled"];
var _hoisted_54 = ["disabled"];
var _hoisted_55 = {
	key: 1,
	class: "-mt-2 mb-4 text-[11px]",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_56 = {
	key: 2,
	class: "h-full flex flex-col items-center justify-center opacity-40"
};
var _hoisted_57 = {
	xmlns: "http://www.w3.org/2000/svg",
	class: "h-16 w-16 mb-4",
	fill: "none",
	viewBox: "0 0 24 24",
	stroke: "currentColor",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_58 = {
	key: 3,
	class: "flex justify-center mt-20"
};
var _hoisted_59 = {
	key: 4,
	class: "space-y-4"
};
var _hoisted_60 = {
	key: 0,
	class: "text-center py-10",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_61 = {
	key: 1,
	class: "overflow-hidden rounded-xl border",
	style: {
		"background-color": "var(--bg-secondary)",
		"border-color": "var(--border-primary)"
	}
};
var _hoisted_62 = { class: "w-full text-left border-collapse" };
var _hoisted_63 = {
	class: "py-3 px-4 text-xs whitespace-nowrap font-mono",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_64 = { class: "py-3 px-4" };
var _hoisted_65 = {
	key: 0,
	class: "mt-1 text-[10px] font-mono",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_66 = { class: "py-3 px-4" };
var _hoisted_67 = {
	key: 0,
	class: "inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold",
	style: {
		"background-color": "color-mix(in srgb, var(--accent-warning) 15%, transparent)",
		"color": "var(--accent-warning)"
	}
};
var _hoisted_68 = {
	key: 1,
	class: "inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold",
	style: {
		"background-color": "color-mix(in srgb, var(--accent-primary) 10%, transparent)",
		"color": "var(--text-tertiary)"
	}
};
var _hoisted_69 = { class: "py-3 px-4" };
var _hoisted_70 = {
	class: "text-xs font-mono px-2 py-1 rounded border",
	style: {
		"background-color": "var(--code-bg)",
		"border-color": "var(--border-primary)",
		"color": "var(--code-text)"
	}
};
var _hoisted_71 = { class: "py-3 px-4" };
var _hoisted_72 = { class: "group" };
var _hoisted_73 = { class: "mt-2" };
var _hoisted_74 = {
	class: "text-[10px] leading-relaxed font-mono p-3 rounded-lg border overflow-x-auto max-h-60 custom-scrollbar",
	style: {
		"background-color": "var(--code-bg)",
		"border-color": "var(--border-primary)",
		"color": "var(--text-secondary)"
	}
};
var _hoisted_75 = {
	key: 2,
	class: "flex items-center justify-between gap-3 px-1"
};
var _hoisted_76 = {
	class: "text-xs",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_77 = { class: "flex items-center gap-2" };
var _hoisted_78 = ["disabled"];
var _hoisted_79 = ["disabled"];
var _hoisted_80 = {
	key: 5,
	class: "space-y-4 max-w-4xl mx-auto"
};
var _hoisted_81 = {
	key: 0,
	class: "text-center py-10",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_82 = { class: "flex-1 min-w-0" };
var _hoisted_83 = { class: "flex items-baseline gap-2 mb-1" };
var _hoisted_84 = {
	class: "text-sm font-semibold",
	style: { "color": "var(--text-primary)" }
};
var _hoisted_85 = {
	class: "text-[10px]",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_86 = ["innerHTML"];
var _hoisted_87 = {
	key: 1,
	class: "flex items-center justify-between gap-3 px-1"
};
var _hoisted_88 = {
	class: "text-xs",
	style: { "color": "var(--text-tertiary)" }
};
var _hoisted_89 = { class: "flex items-center gap-2" };
var _hoisted_90 = ["disabled"];
var _hoisted_91 = ["disabled"];
var DEFAULT_PAGE = 0;
var DEFAULT_SIZE = 100;
var EXPORT_PAGE_SIZE = 500;
var MAX_EXPORT_PAGES = 200;
var AUTO_REFRESH_INTERVAL_MS = 3e4;
var _sfc_main = {
	__name: "AdminDashboard",
	emits: ["close", "conversations-updated"],
	setup(__props, { emit: __emit }) {
		const themeStore = useThemeStore();
		const emit = __emit;
		const md = new lib_default({
			html: false,
			linkify: true,
			typographer: true
		});
		const RESOLVED_CMD_MARKER_REGEX = /\n?\[RESOLVED_CMD:::(?:confirmed|cancelled):::([\s\S]+?):::\]/g;
		const renderMarkdown = (text) => {
			if (!text) return "";
			try {
				return purify.sanitize(md.render(text), {
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
		const hideResolvedCmdMarker = (text) => {
			if (typeof text !== "string" || !text) return text || "";
			return text.replace(RESOLVED_CMD_MARKER_REGEX, "");
		};
		const users = ref([]);
		const selectedUser = ref("");
		const activeTab = ref("audit");
		const auditLogs = ref([]);
		const chatHistory = ref([]);
		const models = ref([]);
		const isLoading = ref(false);
		const isPurging = ref(false);
		const isExporting = ref(false);
		const isManualRefreshing = ref(false);
		const isAutoRefreshing = ref(false);
		const purgeMessage = ref("");
		const exportMessage = ref("");
		const isModelSaving = ref(false);
		const modelMessage = ref("");
		const lastAuditUpdatedAt = ref(null);
		const lastHistoryUpdatedAt = ref(null);
		let autoRefreshTimerId = null;
		const auditPage = ref({
			page: DEFAULT_PAGE,
			size: DEFAULT_SIZE,
			totalElements: 0,
			totalPages: 0,
			hasNext: false
		});
		const historyPage = ref({
			page: DEFAULT_PAGE,
			size: DEFAULT_SIZE,
			totalElements: 0,
			totalPages: 0,
			hasNext: false
		});
		const newModel = ref({
			id: "",
			label: "",
			name: "",
			tpm: 0,
			category: "Other",
			enabled: true
		});
		const fetchUsers = async () => {
			try {
				const res = await httpClient_default.get("/admin/users");
				const payload = Array.isArray(res?.data?.data) ? res.data.data : res?.data;
				users.value = Array.isArray(payload) ? payload : [];
			} catch (e) {
				console.error(e);
			}
		};
		const applyPageState = (target, payload, fallbackPage) => {
			target.value = {
				page: Number.isFinite(payload?.page) ? payload.page : fallbackPage,
				size: Number.isFinite(payload?.size) ? payload.size : target.value.size,
				totalElements: Number.isFinite(payload?.totalElements) ? payload.totalElements : 0,
				totalPages: Number.isFinite(payload?.totalPages) ? payload.totalPages : 0,
				hasNext: Boolean(payload?.hasNext)
			};
		};
		const resetPagedData = () => {
			auditLogs.value = [];
			chatHistory.value = [];
			auditPage.value = {
				page: DEFAULT_PAGE,
				size: DEFAULT_SIZE,
				totalElements: 0,
				totalPages: 0,
				hasNext: false
			};
			historyPage.value = {
				page: DEFAULT_PAGE,
				size: DEFAULT_SIZE,
				totalElements: 0,
				totalPages: 0,
				hasNext: false
			};
			lastAuditUpdatedAt.value = null;
			lastHistoryUpdatedAt.value = null;
		};
		const toMillis = (value) => {
			if (typeof value === "number") return Number.isFinite(value) ? value : 0;
			if (Array.isArray(value)) {
				const [y, mo, d, h = 0, mi = 0, s = 0, ns = 0] = value;
				if (![
					y,
					mo,
					d
				].every(Number.isFinite)) return 0;
				return Date.UTC(y, mo - 1, d, h, mi, s, Math.floor((Number(ns) || 0) / 1e6));
			}
			if (typeof value === "string") {
				const normalized = value.trim().replace(" ", "T");
				const direct = Date.parse(normalized);
				if (Number.isFinite(direct)) return direct;
				const m = normalized.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?$/);
				if (m) {
					const [, y, mo, d, h, mi, s = "0", fraction = "0"] = m;
					const ms = Number((fraction + "000").slice(0, 3));
					return Date.UTC(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi), Number(s), ms);
				}
			}
			if (value instanceof Date) {
				const t = value.getTime();
				return Number.isFinite(t) ? t : 0;
			}
			return 0;
		};
		const sortAuditLogsNewestFirst = (items) => {
			return [...items].sort((a, b) => {
				const timeDiff = toMillis(b?.executionTime) - toMillis(a?.executionTime);
				if (timeDiff !== 0) return timeDiff;
				return Number(b?.id ?? 0) - Number(a?.id ?? 0);
			});
		};
		const sortedAuditLogs = computed(() => sortAuditLogsNewestFirst(auditLogs.value));
		const fetchAuditLogs = async (page = auditPage.value.page, { silent = false } = {}) => {
			if (!selectedUser.value) {
				auditLogs.value = [];
				auditPage.value = {
					page: DEFAULT_PAGE,
					size: DEFAULT_SIZE,
					totalElements: 0,
					totalPages: 0,
					hasNext: false
				};
				lastAuditUpdatedAt.value = null;
				return;
			}
			const safePage = Math.max(page, 0);
			if (!silent) isLoading.value = true;
			try {
				const payload = (await httpClient_default.get(`/admin/audit/${encodeURIComponent(selectedUser.value)}`, { params: {
					page: safePage,
					size: auditPage.value.size
				} }))?.data?.data ?? {};
				auditLogs.value = sortAuditLogsNewestFirst(Array.isArray(payload.items) ? payload.items : []);
				applyPageState(auditPage, payload, safePage);
				lastAuditUpdatedAt.value = Date.now();
			} catch (e) {
				console.error(e);
			} finally {
				if (!silent) isLoading.value = false;
			}
		};
		const fetchHistory = async (page = historyPage.value.page, { silent = false } = {}) => {
			if (!selectedUser.value) {
				chatHistory.value = [];
				historyPage.value = {
					page: DEFAULT_PAGE,
					size: DEFAULT_SIZE,
					totalElements: 0,
					totalPages: 0,
					hasNext: false
				};
				lastHistoryUpdatedAt.value = null;
				return;
			}
			const safePage = Math.max(page, 0);
			if (!silent) isLoading.value = true;
			try {
				const payload = (await httpClient_default.get(`/admin/history/${encodeURIComponent(selectedUser.value)}`, { params: {
					page: safePage,
					size: historyPage.value.size
				} }))?.data?.data ?? {};
				chatHistory.value = Array.isArray(payload.items) ? payload.items : [];
				applyPageState(historyPage, payload, safePage);
				lastHistoryUpdatedAt.value = Date.now();
			} catch (e) {
				console.error(e);
			} finally {
				if (!silent) isLoading.value = false;
			}
		};
		const fetchModels = async () => {
			try {
				models.value = (await httpClient_default.get("/admin/models"))?.data || [];
			} catch (e) {
				console.error(e);
			}
		};
		const saveModel = async (model) => {
			if (!model?.id) {
				modelMessage.value = "Model id 不可為空";
				return;
			}
			isModelSaving.value = true;
			modelMessage.value = "";
			try {
				const payload = {
					...model,
					tpm: Number(model.tpm) || 0,
					category: model.category || "Other"
				};
				await httpClient_default.post("/admin/models", payload);
				modelMessage.value = `已儲存模型：${model.id}`;
				await fetchModels();
			} catch (e) {
				console.error(e);
				modelMessage.value = e?.message || "儲存失敗";
			} finally {
				isModelSaving.value = false;
			}
		};
		const addModel = async () => {
			const m = newModel.value;
			if (!m.id?.trim() || !m.name?.trim() || !m.label?.trim()) {
				modelMessage.value = "請填寫 id / name / label";
				return;
			}
			isModelSaving.value = true;
			modelMessage.value = "";
			try {
				const payload = {
					id: m.id.trim(),
					name: m.name.trim(),
					label: m.label.trim(),
					tpm: Number(m.tpm) || 0,
					category: (m.category || "Other").trim(),
					enabled: !!m.enabled
				};
				await httpClient_default.post("/admin/models", payload);
				modelMessage.value = `已新增模型：${payload.id}`;
				newModel.value = {
					id: "",
					label: "",
					name: "",
					tpm: 0,
					category: "Other",
					enabled: true
				};
				await fetchModels();
			} catch (e) {
				console.error(e);
				modelMessage.value = e?.message || "新增失敗";
			} finally {
				isModelSaving.value = false;
			}
		};
		const deleteModel = async (id) => {
			if (!id) return;
			if (!window.confirm(`確定要刪除模型「${id}」嗎？`)) return;
			isModelSaving.value = true;
			modelMessage.value = "";
			try {
				await httpClient_default.delete(`/admin/models/${encodeURIComponent(id)}`);
				modelMessage.value = `已刪除模型：${id}`;
				await fetchModels();
			} catch (e) {
				console.error(e);
				modelMessage.value = e?.message || "刪除失敗";
			} finally {
				isModelSaving.value = false;
			}
		};
		watch(selectedUser, () => {
			auditPage.value.page = DEFAULT_PAGE;
			historyPage.value.page = DEFAULT_PAGE;
			lastAuditUpdatedAt.value = null;
			lastHistoryUpdatedAt.value = null;
			exportMessage.value = "";
			if (activeTab.value === "audit") fetchAuditLogs(DEFAULT_PAGE);
			if (activeTab.value === "history") fetchHistory(DEFAULT_PAGE);
		});
		watch(activeTab, (newTab) => {
			exportMessage.value = "";
			if (newTab === "models") fetchModels();
			else if (selectedUser.value) {
				if (newTab === "audit") fetchAuditLogs(auditPage.value.page);
				if (newTab === "history") fetchHistory(historyPage.value.page);
			}
		});
		const refreshActiveTabData = async ({ silent = false } = {}) => {
			if (!selectedUser.value || activeTab.value === "models") return;
			if (activeTab.value === "audit") {
				await fetchAuditLogs(auditPage.value.page, { silent });
				return;
			}
			if (activeTab.value === "history") await fetchHistory(historyPage.value.page, { silent });
		};
		const refreshCurrentView = async () => {
			if (!selectedUser.value || isPurging.value || isLoading.value || isAutoRefreshing.value) return;
			isManualRefreshing.value = true;
			try {
				await refreshActiveTabData({ silent: false });
			} finally {
				isManualRefreshing.value = false;
			}
		};
		const startAutoRefresh = () => {
			if (autoRefreshTimerId !== null) return;
			autoRefreshTimerId = window.setInterval(() => {
				if (!selectedUser.value || activeTab.value === "models") return;
				if (typeof document !== "undefined" && document.hidden) return;
				if (isPurging.value || isLoading.value || isManualRefreshing.value || isAutoRefreshing.value) return;
				isAutoRefreshing.value = true;
				refreshActiveTabData({ silent: true }).finally(() => {
					isAutoRefreshing.value = false;
				});
			}, AUTO_REFRESH_INTERVAL_MS);
		};
		const stopAutoRefresh = () => {
			if (autoRefreshTimerId === null) return;
			window.clearInterval(autoRefreshTimerId);
			autoRefreshTimerId = null;
		};
		onMounted(() => {
			fetchUsers();
			startAutoRefresh();
		});
		onUnmounted(() => {
			stopAutoRefresh();
		});
		const formatDate = (ts) => {
			if (!ts) return "-";
			return new Date(ts).toLocaleString();
		};
		const isExportTab = computed(() => activeTab.value === "audit" || activeTab.value === "history");
		const fetchAllPagedItems = async (endpoint) => {
			const allItems = [];
			for (let page = 0; page < MAX_EXPORT_PAGES; page += 1) {
				const payload = (await httpClient_default.get(endpoint, { params: {
					page,
					size: EXPORT_PAGE_SIZE
				} }))?.data?.data ?? {};
				const items = Array.isArray(payload.items) ? payload.items : [];
				allItems.push(...items);
				if (!payload.hasNext) return allItems;
			}
			throw new Error("資料量過大，請縮小範圍後重試");
		};
		const exportSelectedUserCsv = async () => {
			if (!selectedUser.value || !isExportTab.value || isPurging.value) return;
			isExporting.value = true;
			exportMessage.value = "";
			try {
				const username = selectedUser.value;
				const encodedUser = encodeURIComponent(username);
				let csvHeaders = [];
				let csvRows = [];
				let label = "";
				if (activeTab.value === "audit") {
					const items = await fetchAllPagedItems(`/admin/audit/${encodedUser}`);
					csvHeaders = [
						"seq",
						"username",
						"execution_time",
						"status",
						"command_type",
						"command",
						"exit_code",
						"output"
					];
					csvRows = items.map((log, index) => [
						index + 1,
						username,
						log?.executionTime ?? "",
						log?.success ? "SUCCESS" : "FAILED",
						log?.commandType ?? "",
						log?.command ?? "",
						log?.exitCode ?? "",
						hideResolvedCmdMarker(log?.output ?? "")
					]);
					label = "指令紀錄";
				} else {
					const items = await fetchAllPagedItems(`/admin/history/${encodedUser}`);
					csvHeaders = [
						"seq",
						"username",
						"timestamp",
						"role",
						"content"
					];
					csvRows = items.map((msg, index) => [
						index + 1,
						username,
						msg?.timestamp ?? "",
						msg?.role ?? "",
						hideResolvedCmdMarker(msg?.content ?? "")
					]);
					label = "對話紀錄";
				}
				const csvText = buildCsvText(csvHeaders, csvRows);
				downloadTextFile(`${sanitizeFilenamePart(username, "user")}-${activeTab.value}-${buildExportTimestamp()}.csv`, `\uFEFF${csvText}`, "text/csv;charset=utf-8");
				exportMessage.value = `已匯出 ${label} CSV（${csvRows.length} 筆）`;
			} catch (e) {
				console.error(e);
				exportMessage.value = e?.message || "匯出失敗";
			} finally {
				isExporting.value = false;
			}
		};
		const activeLastUpdatedAt = computed(() => {
			if (activeTab.value === "audit") return lastAuditUpdatedAt.value;
			if (activeTab.value === "history") return lastHistoryUpdatedAt.value;
			return null;
		});
		const lastUpdatedLabel = computed(() => {
			if (!activeLastUpdatedAt.value) return "尚未更新";
			return formatDate(activeLastUpdatedAt.value);
		});
		const prevAuditPage = async () => {
			if (auditPage.value.page <= 0 || isLoading.value || isAutoRefreshing.value) return;
			await fetchAuditLogs(auditPage.value.page - 1);
		};
		const nextAuditPage = async () => {
			if (!auditPage.value.hasNext || isLoading.value || isAutoRefreshing.value) return;
			await fetchAuditLogs(auditPage.value.page + 1);
		};
		const prevHistoryPage = async () => {
			if (historyPage.value.page <= 0 || isLoading.value || isAutoRefreshing.value) return;
			await fetchHistory(historyPage.value.page - 1);
		};
		const nextHistoryPage = async () => {
			if (!historyPage.value.hasNext || isLoading.value || isAutoRefreshing.value) return;
			await fetchHistory(historyPage.value.page + 1);
		};
		const notifyConversationsUpdated = () => {
			emit("conversations-updated");
		};
		const purgeSelectedUserChats = async () => {
			if (!selectedUser.value) return;
			if (!window.confirm(`確定要清除使用者「${selectedUser.value}」的「對話紀錄」嗎？此操作無法復原。`)) return;
			isPurging.value = true;
			purgeMessage.value = "";
			try {
				const deletedChat = ((await httpClient_default.delete(`/admin/purge/users/${encodeURIComponent(selectedUser.value)}/chats`))?.data)?.data?.deletedChatMessages ?? 0;
				purgeMessage.value = `已清除 ${selectedUser.value}：對話 ${deletedChat} 筆`;
				notifyConversationsUpdated();
				const before = selectedUser.value;
				await fetchUsers();
				if (!users.value.includes(before)) {
					selectedUser.value = "";
					resetPagedData();
				} else await fetchHistory(DEFAULT_PAGE);
			} catch (e) {
				console.error(e);
				purgeMessage.value = e?.message || "清除失敗";
			} finally {
				isPurging.value = false;
			}
		};
		const purgeSelectedUserCommands = async () => {
			if (!selectedUser.value) return;
			if (!window.confirm(`確定要清除使用者「${selectedUser.value}」的「指令紀錄」嗎？此操作無法復原。`)) return;
			isPurging.value = true;
			purgeMessage.value = "";
			try {
				const deletedCmd = ((await httpClient_default.delete(`/admin/purge/users/${encodeURIComponent(selectedUser.value)}/commands`))?.data)?.data?.deletedCommandLogs ?? 0;
				purgeMessage.value = `已清除 ${selectedUser.value}：指令 ${deletedCmd} 筆`;
				auditLogs.value = [];
				if (activeTab.value === "audit") await fetchAuditLogs(DEFAULT_PAGE);
			} catch (e) {
				console.error(e);
				purgeMessage.value = e?.message || "清除失敗";
			} finally {
				isPurging.value = false;
			}
		};
		const purgeSelectedUserActivity = async () => {
			if (!selectedUser.value) return;
			if (!window.confirm(`確定要清除使用者「${selectedUser.value}」的「對話 + 指令」紀錄嗎？此操作無法復原。`)) return;
			isPurging.value = true;
			purgeMessage.value = "";
			try {
				const user = selectedUser.value;
				const data = (await httpClient_default.delete(`/admin/purge/users/${encodeURIComponent(user)}/activity`))?.data;
				purgeMessage.value = `已清除 ${user}：對話 ${data?.data?.deletedChatMessages ?? 0} 筆、指令 ${data?.data?.deletedCommandLogs ?? 0} 筆`;
				notifyConversationsUpdated();
				await fetchUsers();
				if (!users.value.includes(user)) {
					selectedUser.value = "";
					resetPagedData();
				} else {
					resetPagedData();
					if (activeTab.value === "audit") await fetchAuditLogs(DEFAULT_PAGE);
					if (activeTab.value === "history") await fetchHistory(DEFAULT_PAGE);
				}
			} catch (e) {
				console.error(e);
				purgeMessage.value = e?.message || "清除失敗";
			} finally {
				isPurging.value = false;
			}
		};
		const purgeChats = async () => {
			if (!window.confirm("確定要清除所有「對話紀錄」嗎？此操作無法復原。")) return;
			isPurging.value = true;
			purgeMessage.value = "";
			try {
				purgeMessage.value = `已清除：對話 ${((await httpClient_default.delete("/admin/purge/chats"))?.data)?.data?.deletedChatMessages ?? 0} 筆`;
				notifyConversationsUpdated();
				selectedUser.value = "";
				resetPagedData();
				await fetchUsers();
			} catch (e) {
				console.error(e);
				purgeMessage.value = e?.message || "清除失敗";
			} finally {
				isPurging.value = false;
			}
		};
		const purgeCommands = async () => {
			if (!window.confirm("確定要清除所有「指令執行紀錄」嗎？此操作無法復原。")) return;
			isPurging.value = true;
			purgeMessage.value = "";
			try {
				purgeMessage.value = `已清除：指令 ${((await httpClient_default.delete("/admin/purge/commands"))?.data)?.data?.deletedCommandLogs ?? 0} 筆`;
				auditLogs.value = [];
				if (activeTab.value === "audit" && selectedUser.value) await fetchAuditLogs(DEFAULT_PAGE);
			} catch (e) {
				console.error(e);
				purgeMessage.value = e?.message || "清除失敗";
			} finally {
				isPurging.value = false;
			}
		};
		return (_ctx, _cache) => {
			return openBlock(), createElementBlock("div", _hoisted_1, [createBaseVNode("div", _hoisted_2, [_cache[18] || (_cache[18] = createBaseVNode("div", { class: "flex items-center gap-3" }, [createBaseVNode("h2", { class: "text-xl font-bold" }, "Admin Dashboard"), createBaseVNode("span", {
				class: "text-[10px] px-2 py-0.5 rounded-full font-semibold border",
				style: {
					"background-color": "color-mix(in srgb, var(--accent-danger) 15%, transparent)",
					"color": "var(--accent-danger)",
					"border-color": "color-mix(in srgb, var(--accent-danger) 30%, transparent)"
				}
			}, " Administrator ")], -1)), createBaseVNode("div", _hoisted_3, [createBaseVNode("button", {
				onClick: _cache[0] || (_cache[0] = ($event) => unref(themeStore).toggleTheme()),
				class: "p-2 rounded-lg border transition-all hover:scale-105",
				style: {
					"background-color": "var(--bg-tertiary)",
					"border-color": "var(--border-primary)"
				},
				title: "切換主題"
			}, [unref(themeStore).isDark ? (openBlock(), createElementBlock("svg", _hoisted_4, [..._cache[15] || (_cache[15] = [createBaseVNode("path", {
				"stroke-linecap": "round",
				"stroke-linejoin": "round",
				"stroke-width": "2",
				d: "M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z"
			}, null, -1)])])) : (openBlock(), createElementBlock("svg", _hoisted_5, [..._cache[16] || (_cache[16] = [createBaseVNode("path", {
				"stroke-linecap": "round",
				"stroke-linejoin": "round",
				"stroke-width": "2",
				d: "M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z"
			}, null, -1)])]))]), createBaseVNode("button", {
				onClick: _cache[1] || (_cache[1] = ($event) => _ctx.$emit("close")),
				class: "p-2 rounded-lg transition-colors",
				style: { "color": "var(--text-tertiary)" },
				onMouseenter: _cache[2] || (_cache[2] = ($event) => $event.target.style.backgroundColor = "var(--bg-tertiary)"),
				onMouseleave: _cache[3] || (_cache[3] = ($event) => $event.target.style.backgroundColor = "transparent")
			}, [..._cache[17] || (_cache[17] = [createBaseVNode("svg", {
				xmlns: "http://www.w3.org/2000/svg",
				class: "h-5 w-5",
				fill: "none",
				viewBox: "0 0 24 24",
				stroke: "currentColor"
			}, [createBaseVNode("path", {
				"stroke-linecap": "round",
				"stroke-linejoin": "round",
				"stroke-width": "2",
				d: "M6 18L18 6M6 6l12 12"
			})], -1)])], 32)])]), createBaseVNode("div", _hoisted_6, [createBaseVNode("div", _hoisted_7, [
				_cache[19] || (_cache[19] = createBaseVNode("div", {
					class: "p-4 border-b",
					style: { "border-color": "var(--border-primary)" }
				}, [createBaseVNode("h3", {
					class: "text-[10px] font-bold uppercase tracking-widest",
					style: { "color": "var(--text-tertiary)" }
				}, "Users")], -1)),
				createBaseVNode("div", _hoisted_8, [(openBlock(true), createElementBlock(Fragment, null, renderList(users.value, (user) => {
					return openBlock(), createElementBlock("button", {
						key: user,
						onClick: ($event) => selectedUser.value = user,
						class: "w-full text-left px-3 py-2 rounded-lg text-sm transition-all flex items-center gap-2",
						style: normalizeStyle(selectedUser.value === user ? {
							backgroundColor: "var(--accent-primary)",
							color: "white"
						} : { color: "var(--text-secondary)" }),
						onMouseenter: ($event) => selectedUser.value !== user && ($event.currentTarget.style.backgroundColor = "var(--bg-tertiary)"),
						onMouseleave: ($event) => selectedUser.value !== user && ($event.currentTarget.style.backgroundColor = "transparent")
					}, [createBaseVNode("span", {
						class: "w-6 h-6 rounded-full flex items-center justify-center text-[10px] font-bold flex-shrink-0",
						style: normalizeStyle(selectedUser.value === user ? {
							backgroundColor: "rgba(255,255,255,0.2)",
							color: "white"
						} : {
							backgroundColor: "var(--bg-tertiary)",
							color: "var(--text-tertiary)"
						})
					}, toDisplayString(user.charAt(0).toUpperCase()), 5), createTextVNode(" " + toDisplayString(user), 1)], 44, _hoisted_9);
				}), 128))]),
				createBaseVNode("div", _hoisted_10, [
					createBaseVNode("button", {
						onClick: purgeChats,
						disabled: isPurging.value,
						class: "w-full text-left px-3 py-2 rounded-lg text-xs transition-colors border disabled:opacity-50 disabled:cursor-not-allowed",
						style: {
							"border-color": "color-mix(in srgb, var(--accent-danger) 30%, transparent)",
							"color": "var(--accent-danger)"
						}
					}, " 清除所有對話 ", 8, _hoisted_11),
					createBaseVNode("button", {
						onClick: purgeCommands,
						disabled: isPurging.value,
						class: "w-full text-left px-3 py-2 rounded-lg text-xs transition-colors border disabled:opacity-50 disabled:cursor-not-allowed",
						style: {
							"border-color": "color-mix(in srgb, var(--accent-danger) 30%, transparent)",
							"color": "var(--accent-danger)"
						}
					}, " 清除所有指令 ", 8, _hoisted_12),
					purgeMessage.value ? (openBlock(), createElementBlock("div", _hoisted_13, toDisplayString(purgeMessage.value), 1)) : createCommentVNode("", true)
				])
			]), createBaseVNode("div", _hoisted_14, [createBaseVNode("div", _hoisted_15, [
				createBaseVNode("button", {
					onClick: _cache[4] || (_cache[4] = ($event) => activeTab.value = "audit"),
					class: "px-6 py-3 text-sm font-medium border-b-2 transition-colors",
					style: normalizeStyle(activeTab.value === "audit" ? {
						borderColor: "var(--accent-primary)",
						color: "var(--accent-primary)"
					} : {
						borderColor: "transparent",
						color: "var(--text-tertiary)"
					})
				}, " 指令審計 ", 4),
				createBaseVNode("button", {
					onClick: _cache[5] || (_cache[5] = ($event) => activeTab.value = "history"),
					class: "px-6 py-3 text-sm font-medium border-b-2 transition-colors",
					style: normalizeStyle(activeTab.value === "history" ? {
						borderColor: "var(--accent-primary)",
						color: "var(--accent-primary)"
					} : {
						borderColor: "transparent",
						color: "var(--text-tertiary)"
					})
				}, " 對話紀錄 ", 4),
				createBaseVNode("button", {
					onClick: _cache[6] || (_cache[6] = ($event) => activeTab.value = "models"),
					class: "px-6 py-3 text-sm font-medium border-b-2 transition-colors ml-auto",
					style: normalizeStyle(activeTab.value === "models" ? {
						borderColor: "var(--accent-primary)",
						color: "var(--accent-primary)"
					} : {
						borderColor: "transparent",
						color: "var(--text-tertiary)"
					})
				}, " 模型設定 ", 4)
			]), activeTab.value === "models" ? (openBlock(), createElementBlock("div", _hoisted_16, [
				_cache[33] || (_cache[33] = createBaseVNode("div", { class: "flex items-baseline justify-between gap-4 mb-6" }, [createBaseVNode("h3", { class: "text-2xl font-bold" }, "AI 模型配置"), createBaseVNode("div", {
					class: "text-xs",
					style: { "color": "var(--text-tertiary)" }
				}, "提示：修改會寫入 DB；重啟不會被 application.properties 覆蓋")], -1)),
				createBaseVNode("div", _hoisted_17, [
					createBaseVNode("div", _hoisted_18, [_cache[20] || (_cache[20] = createBaseVNode("div", { class: "font-semibold text-sm" }, "新增模型", -1)), createBaseVNode("button", {
						onClick: addModel,
						disabled: isModelSaving.value,
						class: "px-3 py-1.5 rounded-lg text-xs font-medium text-white disabled:opacity-50 disabled:cursor-not-allowed",
						style: { "background-color": "var(--accent-primary)" }
					}, " 新增 ", 8, _hoisted_19)]),
					createBaseVNode("div", _hoisted_20, [
						createBaseVNode("label", _hoisted_21, [_cache[21] || (_cache[21] = createTextVNode(" id ", -1)), withDirectives(createBaseVNode("input", {
							"onUpdate:modelValue": _cache[7] || (_cache[7] = ($event) => newModel.value.id = $event),
							class: "admin-input mt-1",
							placeholder: "e.g. 70b"
						}, null, 512), [[vModelText, newModel.value.id]])]),
						createBaseVNode("label", _hoisted_22, [_cache[22] || (_cache[22] = createTextVNode(" label ", -1)), withDirectives(createBaseVNode("input", {
							"onUpdate:modelValue": _cache[8] || (_cache[8] = ($event) => newModel.value.label = $event),
							class: "admin-input mt-1",
							placeholder: "顯示名稱"
						}, null, 512), [[vModelText, newModel.value.label]])]),
						createBaseVNode("label", _hoisted_23, [_cache[23] || (_cache[23] = createTextVNode(" name (provider model name) ", -1)), withDirectives(createBaseVNode("input", {
							"onUpdate:modelValue": _cache[9] || (_cache[9] = ($event) => newModel.value.name = $event),
							class: "admin-input mt-1 font-mono",
							placeholder: "e.g. llama-3.3-70b-versatile"
						}, null, 512), [[vModelText, newModel.value.name]])]),
						createBaseVNode("label", _hoisted_24, [_cache[24] || (_cache[24] = createTextVNode(" tpm ", -1)), withDirectives(createBaseVNode("input", {
							"onUpdate:modelValue": _cache[10] || (_cache[10] = ($event) => newModel.value.tpm = $event),
							type: "number",
							min: "0",
							class: "admin-input mt-1 font-mono"
						}, null, 512), [[
							vModelText,
							newModel.value.tpm,
							void 0,
							{ number: true }
						]])]),
						createBaseVNode("label", _hoisted_25, [_cache[25] || (_cache[25] = createTextVNode(" category ", -1)), withDirectives(createBaseVNode("input", {
							"onUpdate:modelValue": _cache[11] || (_cache[11] = ($event) => newModel.value.category = $event),
							class: "admin-input mt-1",
							placeholder: "Other"
						}, null, 512), [[vModelText, newModel.value.category]])]),
						createBaseVNode("label", _hoisted_26, [withDirectives(createBaseVNode("input", {
							"onUpdate:modelValue": _cache[12] || (_cache[12] = ($event) => newModel.value.enabled = $event),
							type: "checkbox",
							class: "accent-[var(--accent-primary)]"
						}, null, 512), [[vModelCheckbox, newModel.value.enabled]]), _cache[26] || (_cache[26] = createTextVNode(" enabled ", -1))])
					]),
					modelMessage.value ? (openBlock(), createElementBlock("div", _hoisted_27, toDisplayString(modelMessage.value), 1)) : createCommentVNode("", true)
				]),
				createBaseVNode("div", _hoisted_28, [(openBlock(true), createElementBlock(Fragment, null, renderList(models.value, (m) => {
					return openBlock(), createElementBlock("div", {
						key: m.id,
						class: "p-5 rounded-xl border",
						style: {
							"background-color": "var(--bg-secondary)",
							"border-color": "var(--border-primary)"
						}
					}, [createBaseVNode("div", _hoisted_29, [createBaseVNode("div", _hoisted_30, [_cache[27] || (_cache[27] = createBaseVNode("div", {
						class: "text-[10px]",
						style: { "color": "var(--text-tertiary)" }
					}, "id", -1)), createBaseVNode("div", _hoisted_31, toDisplayString(m.id), 1)]), createBaseVNode("div", _hoisted_32, [
						createBaseVNode("label", _hoisted_33, [withDirectives(createBaseVNode("input", {
							"onUpdate:modelValue": ($event) => m.enabled = $event,
							type: "checkbox",
							class: "accent-[var(--accent-primary)]"
						}, null, 8, _hoisted_34), [[vModelCheckbox, m.enabled]]), _cache[28] || (_cache[28] = createTextVNode(" enabled ", -1))]),
						createBaseVNode("button", {
							onClick: ($event) => saveModel(m),
							disabled: isModelSaving.value,
							class: "px-3 py-1.5 rounded-lg text-xs font-medium text-white disabled:opacity-50 disabled:cursor-not-allowed",
							style: { "background-color": "var(--accent-primary)" }
						}, " 儲存 ", 8, _hoisted_35),
						createBaseVNode("button", {
							onClick: ($event) => deleteModel(m.id),
							disabled: isModelSaving.value,
							class: "px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed",
							style: {
								"border-color": "color-mix(in srgb, var(--accent-danger) 30%, transparent)",
								"color": "var(--accent-danger)"
							}
						}, " 刪除 ", 8, _hoisted_36)
					])]), createBaseVNode("div", _hoisted_37, [
						createBaseVNode("label", _hoisted_38, [_cache[29] || (_cache[29] = createTextVNode(" label ", -1)), withDirectives(createBaseVNode("input", {
							"onUpdate:modelValue": ($event) => m.label = $event,
							class: "admin-input mt-1"
						}, null, 8, _hoisted_39), [[vModelText, m.label]])]),
						createBaseVNode("label", _hoisted_40, [_cache[30] || (_cache[30] = createTextVNode(" category ", -1)), withDirectives(createBaseVNode("input", {
							"onUpdate:modelValue": ($event) => m.category = $event,
							class: "admin-input mt-1"
						}, null, 8, _hoisted_41), [[vModelText, m.category]])]),
						createBaseVNode("label", _hoisted_42, [_cache[31] || (_cache[31] = createTextVNode(" name ", -1)), withDirectives(createBaseVNode("input", {
							"onUpdate:modelValue": ($event) => m.name = $event,
							class: "admin-input mt-1 font-mono"
						}, null, 8, _hoisted_43), [[vModelText, m.name]])]),
						createBaseVNode("label", _hoisted_44, [_cache[32] || (_cache[32] = createTextVNode(" tpm ", -1)), withDirectives(createBaseVNode("input", {
							"onUpdate:modelValue": ($event) => m.tpm = $event,
							type: "number",
							min: "0",
							class: "admin-input mt-1 font-mono"
						}, null, 8, _hoisted_45), [[
							vModelText,
							m.tpm,
							void 0,
							{ number: true }
						]])])
					])]);
				}), 128))])
			])) : (openBlock(), createElementBlock("div", _hoisted_46, [
				selectedUser.value ? (openBlock(), createElementBlock("div", _hoisted_47, [
					_cache[34] || (_cache[34] = createBaseVNode("span", {
						class: "text-xs",
						style: { "color": "var(--text-tertiary)" }
					}, "Selected:", -1)),
					createBaseVNode("span", _hoisted_48, toDisplayString(selectedUser.value), 1),
					createBaseVNode("span", _hoisted_49, " 最後更新：" + toDisplayString(lastUpdatedLabel.value) + "（每 30 秒自動更新） ", 1),
					createBaseVNode("button", {
						onClick: refreshCurrentView,
						disabled: isPurging.value || isLoading.value || isManualRefreshing.value || isAutoRefreshing.value || isExporting.value,
						class: "ml-auto px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed",
						style: {
							"background-color": "var(--bg-secondary)",
							"border-color": "var(--border-primary)",
							"color": "var(--text-secondary)"
						}
					}, toDisplayString(isManualRefreshing.value || isAutoRefreshing.value ? "更新中..." : "重新整理"), 9, _hoisted_50),
					createBaseVNode("button", {
						onClick: exportSelectedUserCsv,
						disabled: isPurging.value || isLoading.value || isExporting.value || !isExportTab.value,
						class: "px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed",
						style: {
							"border-color": "color-mix(in srgb, var(--accent-primary) 35%, transparent)",
							"color": "var(--accent-primary)"
						}
					}, toDisplayString(isExporting.value ? "匯出中..." : "匯出 CSV"), 9, _hoisted_51),
					createBaseVNode("button", {
						onClick: purgeSelectedUserChats,
						disabled: isPurging.value || isExporting.value,
						class: "px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed",
						style: {
							"border-color": "color-mix(in srgb, var(--accent-danger) 30%, transparent)",
							"color": "var(--accent-danger)"
						}
					}, " 清除對話 ", 8, _hoisted_52),
					createBaseVNode("button", {
						onClick: purgeSelectedUserCommands,
						disabled: isPurging.value || isExporting.value,
						class: "px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed",
						style: {
							"border-color": "color-mix(in srgb, var(--accent-danger) 30%, transparent)",
							"color": "var(--accent-danger)"
						}
					}, " 清除指令 ", 8, _hoisted_53),
					createBaseVNode("button", {
						onClick: purgeSelectedUserActivity,
						disabled: isPurging.value || isExporting.value,
						class: "px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed",
						style: {
							"border-color": "color-mix(in srgb, var(--accent-danger) 30%, transparent)",
							"color": "var(--accent-danger)"
						}
					}, " 清除全部 ", 8, _hoisted_54)
				])) : createCommentVNode("", true),
				exportMessage.value ? (openBlock(), createElementBlock("div", _hoisted_55, toDisplayString(exportMessage.value), 1)) : createCommentVNode("", true),
				!selectedUser.value ? (openBlock(), createElementBlock("div", _hoisted_56, [(openBlock(), createElementBlock("svg", _hoisted_57, [..._cache[35] || (_cache[35] = [createBaseVNode("path", {
					"stroke-linecap": "round",
					"stroke-linejoin": "round",
					"stroke-width": "1.5",
					d: "M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z"
				}, null, -1)])])), _cache[36] || (_cache[36] = createBaseVNode("p", { style: { "color": "var(--text-tertiary)" } }, "請從左側選擇一位使用者", -1))])) : isLoading.value ? (openBlock(), createElementBlock("div", _hoisted_58, [..._cache[37] || (_cache[37] = [createBaseVNode("div", {
					class: "animate-spin rounded-full h-8 w-8 border-b-2",
					style: { "border-color": "var(--accent-primary)" }
				}, null, -1)])])) : activeTab.value === "audit" ? (openBlock(), createElementBlock("div", _hoisted_59, [auditLogs.value.length === 0 ? (openBlock(), createElementBlock("div", _hoisted_60, "尚無指令執行紀錄")) : (openBlock(), createElementBlock("div", _hoisted_61, [createBaseVNode("table", _hoisted_62, [_cache[39] || (_cache[39] = createBaseVNode("thead", null, [createBaseVNode("tr", { style: { "background-color": "var(--bg-tertiary)" } }, [
					createBaseVNode("th", {
						class: "py-3 px-4 border-b text-xs uppercase font-semibold",
						style: {
							"border-color": "var(--border-primary)",
							"color": "var(--text-tertiary)"
						}
					}, "時間"),
					createBaseVNode("th", {
						class: "py-3 px-4 border-b text-xs uppercase font-semibold",
						style: {
							"border-color": "var(--border-primary)",
							"color": "var(--text-tertiary)"
						}
					}, "狀態"),
					createBaseVNode("th", {
						class: "py-3 px-4 border-b text-xs uppercase font-semibold",
						style: {
							"border-color": "var(--border-primary)",
							"color": "var(--text-tertiary)"
						}
					}, "類型"),
					createBaseVNode("th", {
						class: "py-3 px-4 border-b text-xs uppercase font-semibold",
						style: {
							"border-color": "var(--border-primary)",
							"color": "var(--text-tertiary)"
						}
					}, "指令"),
					createBaseVNode("th", {
						class: "py-3 px-4 border-b text-xs uppercase font-semibold",
						style: {
							"border-color": "var(--border-primary)",
							"color": "var(--text-tertiary)"
						}
					}, "輸出結果")
				])], -1)), createBaseVNode("tbody", null, [(openBlock(true), createElementBlock(Fragment, null, renderList(sortedAuditLogs.value, (log) => {
					return openBlock(), createElementBlock("tr", {
						key: log.id,
						class: "transition-colors border-b",
						style: { "border-color": "var(--border-primary)" },
						onMouseenter: _cache[13] || (_cache[13] = ($event) => $event.currentTarget.style.backgroundColor = "var(--bg-tertiary)"),
						onMouseleave: _cache[14] || (_cache[14] = ($event) => $event.currentTarget.style.backgroundColor = "transparent")
					}, [
						createBaseVNode("td", _hoisted_63, toDisplayString(formatDate(log.executionTime)), 1),
						createBaseVNode("td", _hoisted_64, [createBaseVNode("span", {
							class: "inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold",
							style: normalizeStyle(log.success ? {
								backgroundColor: "color-mix(in srgb, var(--accent-success) 15%, transparent)",
								color: "var(--accent-success)"
							} : {
								backgroundColor: "color-mix(in srgb, var(--accent-danger) 15%, transparent)",
								color: "var(--accent-danger)"
							})
						}, toDisplayString(log.success ? "SUCCESS" : "FAILED"), 5), !log.success && log.exitCode !== null && log.exitCode !== void 0 ? (openBlock(), createElementBlock("div", _hoisted_65, " exit: " + toDisplayString(log.exitCode), 1)) : createCommentVNode("", true)]),
						createBaseVNode("td", _hoisted_66, [log.commandType === "MODIFY" ? (openBlock(), createElementBlock("span", _hoisted_67, " MODIFY ")) : (openBlock(), createElementBlock("span", _hoisted_68, " READ "))]),
						createBaseVNode("td", _hoisted_69, [createBaseVNode("code", _hoisted_70, toDisplayString(log.command), 1)]),
						createBaseVNode("td", _hoisted_71, [createBaseVNode("details", _hoisted_72, [_cache[38] || (_cache[38] = createBaseVNode("summary", {
							class: "cursor-pointer text-xs select-none flex items-center gap-1",
							style: { "color": "var(--text-tertiary)" }
						}, [createBaseVNode("span", { class: "group-open:hidden" }, "▶ 顯示輸出"), createBaseVNode("span", { class: "hidden group-open:inline" }, "▼ 隱藏輸出")], -1)), createBaseVNode("div", _hoisted_73, [createBaseVNode("pre", _hoisted_74, toDisplayString(hideResolvedCmdMarker(log.output)), 1)])])])
					], 32);
				}), 128))])])])), auditPage.value.totalElements > 0 ? (openBlock(), createElementBlock("div", _hoisted_75, [createBaseVNode("span", _hoisted_76, " 第 " + toDisplayString(auditPage.value.page + 1) + " / " + toDisplayString(Math.max(auditPage.value.totalPages, 1)) + " 頁，共 " + toDisplayString(auditPage.value.totalElements) + " 筆 ", 1), createBaseVNode("div", _hoisted_77, [createBaseVNode("button", {
					onClick: prevAuditPage,
					disabled: isLoading.value || isAutoRefreshing.value || auditPage.value.page <= 0,
					class: "px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed",
					style: {
						"background-color": "var(--bg-secondary)",
						"border-color": "var(--border-primary)",
						"color": "var(--text-secondary)"
					}
				}, " 上一頁 ", 8, _hoisted_78), createBaseVNode("button", {
					onClick: nextAuditPage,
					disabled: isLoading.value || isAutoRefreshing.value || !auditPage.value.hasNext,
					class: "px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed",
					style: {
						"background-color": "var(--bg-secondary)",
						"border-color": "var(--border-primary)",
						"color": "var(--text-secondary)"
					}
				}, " 下一頁 ", 8, _hoisted_79)])])) : createCommentVNode("", true)])) : activeTab.value === "history" ? (openBlock(), createElementBlock("div", _hoisted_80, [
					chatHistory.value.length === 0 ? (openBlock(), createElementBlock("div", _hoisted_81, "尚無對話紀錄")) : createCommentVNode("", true),
					(openBlock(true), createElementBlock(Fragment, null, renderList(chatHistory.value, (msg, idx) => {
						return openBlock(), createElementBlock("div", {
							key: idx,
							class: "flex gap-3"
						}, [createBaseVNode("div", { class: normalizeClass(["flex-shrink-0 w-7 h-7 rounded-lg flex items-center justify-center text-[10px] font-bold", msg.role === "user" ? "bg-gradient-to-br from-indigo-500 to-purple-600 text-white" : "bg-gradient-to-br from-emerald-500 to-teal-600 text-white"]) }, toDisplayString(msg.role === "user" ? "U" : "AI"), 3), createBaseVNode("div", _hoisted_82, [createBaseVNode("div", _hoisted_83, [createBaseVNode("span", _hoisted_84, toDisplayString(msg.role === "user" ? "User" : "Assistant"), 1), createBaseVNode("span", _hoisted_85, toDisplayString(formatDate(msg.timestamp)), 1)]), createBaseVNode("div", {
							class: "markdown-content rounded-xl p-4 border text-sm leading-relaxed shadow-sm",
							style: {
								"background-color": "var(--bg-secondary)",
								"border-color": "var(--border-primary)",
								"color": "var(--text-secondary)"
							},
							innerHTML: renderMarkdown(msg.content)
						}, null, 8, _hoisted_86)])]);
					}), 128)),
					historyPage.value.totalElements > 0 ? (openBlock(), createElementBlock("div", _hoisted_87, [createBaseVNode("span", _hoisted_88, " 第 " + toDisplayString(historyPage.value.page + 1) + " / " + toDisplayString(Math.max(historyPage.value.totalPages, 1)) + " 頁，共 " + toDisplayString(historyPage.value.totalElements) + " 筆 ", 1), createBaseVNode("div", _hoisted_89, [createBaseVNode("button", {
						onClick: prevHistoryPage,
						disabled: isLoading.value || isAutoRefreshing.value || historyPage.value.page <= 0,
						class: "px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed",
						style: {
							"background-color": "var(--bg-secondary)",
							"border-color": "var(--border-primary)",
							"color": "var(--text-secondary)"
						}
					}, " 上一頁 ", 8, _hoisted_90), createBaseVNode("button", {
						onClick: nextHistoryPage,
						disabled: isLoading.value || isAutoRefreshing.value || !historyPage.value.hasNext,
						class: "px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed",
						style: {
							"background-color": "var(--bg-secondary)",
							"border-color": "var(--border-primary)",
							"color": "var(--text-secondary)"
						}
					}, " 下一頁 ", 8, _hoisted_91)])])) : createCommentVNode("", true)
				])) : createCommentVNode("", true)
			]))])])]);
		};
	}
};
var AdminDashboard_default = /* @__PURE__ */ __plugin_vue_export_helper_default(_sfc_main, [["__scopeId", "data-v-302773b4"]]);

//#endregion
export { AdminDashboard_default as default };