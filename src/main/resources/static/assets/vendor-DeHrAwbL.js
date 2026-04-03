import { t as __export } from "./rolldown-runtime-Cj0C9Eap.js";

//#region node_modules/@tanstack/virtual-core/dist/esm/utils.js
function memo(getDeps, fn, opts) {
	let deps = opts.initialDeps ?? [];
	let result;
	let isInitial = true;
	function memoizedFunction() {
		var _a, _b, _c;
		let depTime;
		if (opts.key && ((_a = opts.debug) == null ? void 0 : _a.call(opts))) depTime = Date.now();
		const newDeps = getDeps();
		if (!(newDeps.length !== deps.length || newDeps.some((dep, index) => deps[index] !== dep))) return result;
		deps = newDeps;
		let resultTime;
		if (opts.key && ((_b = opts.debug) == null ? void 0 : _b.call(opts))) resultTime = Date.now();
		result = fn(...newDeps);
		if (opts.key && ((_c = opts.debug) == null ? void 0 : _c.call(opts))) {
			const depEndTime = Math.round((Date.now() - depTime) * 100) / 100;
			const resultEndTime = Math.round((Date.now() - resultTime) * 100) / 100;
			const resultFpsPercentage = resultEndTime / 16;
			const pad = (str, num) => {
				str = String(str);
				while (str.length < num) str = " " + str;
				return str;
			};
			console.info(`%c⏱ ${pad(resultEndTime, 5)} /${pad(depEndTime, 5)} ms`, `
            font-size: .6rem;
            font-weight: bold;
            color: hsl(${Math.max(0, Math.min(120 - 120 * resultFpsPercentage, 120))}deg 100% 31%);`, opts == null ? void 0 : opts.key);
		}
		if ((opts == null ? void 0 : opts.onChange) && !(isInitial && opts.skipInitialOnChange)) opts.onChange(result);
		isInitial = false;
		return result;
	}
	memoizedFunction.updateDeps = (newDeps) => {
		deps = newDeps;
	};
	return memoizedFunction;
}
function notUndefined(value, msg) {
	if (value === void 0) throw new Error(`Unexpected undefined${msg ? `: ${msg}` : ""}`);
	else return value;
}
var approxEqual = (a, b) => Math.abs(a - b) < 1.01;
var debounce = (targetWindow, fn, ms) => {
	let timeoutId;
	return function(...args) {
		targetWindow.clearTimeout(timeoutId);
		timeoutId = targetWindow.setTimeout(() => fn.apply(this, args), ms);
	};
};

//#endregion
//#region node_modules/@tanstack/virtual-core/dist/esm/index.js
var getRect = (element) => {
	const { offsetWidth, offsetHeight } = element;
	return {
		width: offsetWidth,
		height: offsetHeight
	};
};
var defaultKeyExtractor = (index) => index;
var defaultRangeExtractor = (range) => {
	const start = Math.max(range.startIndex - range.overscan, 0);
	const end = Math.min(range.endIndex + range.overscan, range.count - 1);
	const arr = [];
	for (let i = start; i <= end; i++) arr.push(i);
	return arr;
};
var observeElementRect = (instance, cb) => {
	const element = instance.scrollElement;
	if (!element) return;
	const targetWindow = instance.targetWindow;
	if (!targetWindow) return;
	const handler = (rect) => {
		const { width, height } = rect;
		cb({
			width: Math.round(width),
			height: Math.round(height)
		});
	};
	handler(getRect(element));
	if (!targetWindow.ResizeObserver) return () => {};
	const observer = new targetWindow.ResizeObserver((entries$1) => {
		const run = () => {
			const entry = entries$1[0];
			if (entry == null ? void 0 : entry.borderBoxSize) {
				const box = entry.borderBoxSize[0];
				if (box) {
					handler({
						width: box.inlineSize,
						height: box.blockSize
					});
					return;
				}
			}
			handler(getRect(element));
		};
		instance.options.useAnimationFrameWithResizeObserver ? requestAnimationFrame(run) : run();
	});
	observer.observe(element, { box: "border-box" });
	return () => {
		observer.unobserve(element);
	};
};
var addEventListenerOptions = { passive: true };
var supportsScrollend = typeof window == "undefined" ? true : "onscrollend" in window;
var observeElementOffset = (instance, cb) => {
	const element = instance.scrollElement;
	if (!element) return;
	const targetWindow = instance.targetWindow;
	if (!targetWindow) return;
	let offset = 0;
	const fallback = instance.options.useScrollendEvent && supportsScrollend ? () => void 0 : debounce(targetWindow, () => {
		cb(offset, false);
	}, instance.options.isScrollingResetDelay);
	const createHandler = (isScrolling) => () => {
		const { horizontal, isRtl } = instance.options;
		offset = horizontal ? element["scrollLeft"] * (isRtl && -1 || 1) : element["scrollTop"];
		fallback();
		cb(offset, isScrolling);
	};
	const handler = createHandler(true);
	const endHandler = createHandler(false);
	element.addEventListener("scroll", handler, addEventListenerOptions);
	const registerScrollendEvent = instance.options.useScrollendEvent && supportsScrollend;
	if (registerScrollendEvent) element.addEventListener("scrollend", endHandler, addEventListenerOptions);
	return () => {
		element.removeEventListener("scroll", handler);
		if (registerScrollendEvent) element.removeEventListener("scrollend", endHandler);
	};
};
var measureElement = (element, entry, instance) => {
	if (entry == null ? void 0 : entry.borderBoxSize) {
		const box = entry.borderBoxSize[0];
		if (box) return Math.round(box[instance.options.horizontal ? "inlineSize" : "blockSize"]);
	}
	return element[instance.options.horizontal ? "offsetWidth" : "offsetHeight"];
};
var elementScroll = (offset, { adjustments = 0, behavior }, instance) => {
	var _a, _b;
	const toOffset = offset + adjustments;
	(_b = (_a = instance.scrollElement) == null ? void 0 : _a.scrollTo) == null || _b.call(_a, {
		[instance.options.horizontal ? "left" : "top"]: toOffset,
		behavior
	});
};
var Virtualizer = class {
	constructor(opts) {
		this.unsubs = [];
		this.scrollElement = null;
		this.targetWindow = null;
		this.isScrolling = false;
		this.scrollState = null;
		this.measurementsCache = [];
		this.itemSizeCache = /* @__PURE__ */ new Map();
		this.laneAssignments = /* @__PURE__ */ new Map();
		this.pendingMeasuredCacheIndexes = [];
		this.prevLanes = void 0;
		this.lanesChangedFlag = false;
		this.lanesSettling = false;
		this.scrollRect = null;
		this.scrollOffset = null;
		this.scrollDirection = null;
		this.scrollAdjustments = 0;
		this.elementsCache = /* @__PURE__ */ new Map();
		this.now = () => {
			var _a, _b, _c;
			return ((_c = (_b = (_a = this.targetWindow) == null ? void 0 : _a.performance) == null ? void 0 : _b.now) == null ? void 0 : _c.call(_b)) ?? Date.now();
		};
		this.observer = /* @__PURE__ */ (() => {
			let _ro = null;
			const get = () => {
				if (_ro) return _ro;
				if (!this.targetWindow || !this.targetWindow.ResizeObserver) return null;
				return _ro = new this.targetWindow.ResizeObserver((entries$1) => {
					entries$1.forEach((entry) => {
						const run = () => {
							const node = entry.target;
							const index = this.indexFromElement(node);
							if (!node.isConnected) {
								this.observer.unobserve(node);
								return;
							}
							if (this.shouldMeasureDuringScroll(index)) this.resizeItem(index, this.options.measureElement(node, entry, this));
						};
						this.options.useAnimationFrameWithResizeObserver ? requestAnimationFrame(run) : run();
					});
				});
			};
			return {
				disconnect: () => {
					var _a;
					(_a = get()) == null || _a.disconnect();
					_ro = null;
				},
				observe: (target) => {
					var _a;
					return (_a = get()) == null ? void 0 : _a.observe(target, { box: "border-box" });
				},
				unobserve: (target) => {
					var _a;
					return (_a = get()) == null ? void 0 : _a.unobserve(target);
				}
			};
		})();
		this.range = null;
		this.setOptions = (opts2) => {
			Object.entries(opts2).forEach(([key, value]) => {
				if (typeof value === "undefined") delete opts2[key];
			});
			this.options = {
				debug: false,
				initialOffset: 0,
				overscan: 1,
				paddingStart: 0,
				paddingEnd: 0,
				scrollPaddingStart: 0,
				scrollPaddingEnd: 0,
				horizontal: false,
				getItemKey: defaultKeyExtractor,
				rangeExtractor: defaultRangeExtractor,
				onChange: () => {},
				measureElement,
				initialRect: {
					width: 0,
					height: 0
				},
				scrollMargin: 0,
				gap: 0,
				indexAttribute: "data-index",
				initialMeasurementsCache: [],
				lanes: 1,
				isScrollingResetDelay: 150,
				enabled: true,
				isRtl: false,
				useScrollendEvent: false,
				useAnimationFrameWithResizeObserver: false,
				...opts2
			};
		};
		this.notify = (sync) => {
			var _a, _b;
			(_b = (_a = this.options).onChange) == null || _b.call(_a, this, sync);
		};
		this.maybeNotify = memo(() => {
			this.calculateRange();
			return [
				this.isScrolling,
				this.range ? this.range.startIndex : null,
				this.range ? this.range.endIndex : null
			];
		}, (isScrolling) => {
			this.notify(isScrolling);
		}, {
			key: false,
			debug: () => this.options.debug,
			initialDeps: [
				this.isScrolling,
				this.range ? this.range.startIndex : null,
				this.range ? this.range.endIndex : null
			]
		});
		this.cleanup = () => {
			this.unsubs.filter(Boolean).forEach((d) => d());
			this.unsubs = [];
			this.observer.disconnect();
			if (this.rafId != null && this.targetWindow) {
				this.targetWindow.cancelAnimationFrame(this.rafId);
				this.rafId = null;
			}
			this.scrollState = null;
			this.scrollElement = null;
			this.targetWindow = null;
		};
		this._didMount = () => {
			return () => {
				this.cleanup();
			};
		};
		this._willUpdate = () => {
			var _a;
			const scrollElement = this.options.enabled ? this.options.getScrollElement() : null;
			if (this.scrollElement !== scrollElement) {
				this.cleanup();
				if (!scrollElement) {
					this.maybeNotify();
					return;
				}
				this.scrollElement = scrollElement;
				if (this.scrollElement && "ownerDocument" in this.scrollElement) this.targetWindow = this.scrollElement.ownerDocument.defaultView;
				else this.targetWindow = ((_a = this.scrollElement) == null ? void 0 : _a.window) ?? null;
				this.elementsCache.forEach((cached) => {
					this.observer.observe(cached);
				});
				this.unsubs.push(this.options.observeElementRect(this, (rect) => {
					this.scrollRect = rect;
					this.maybeNotify();
				}));
				this.unsubs.push(this.options.observeElementOffset(this, (offset, isScrolling) => {
					this.scrollAdjustments = 0;
					this.scrollDirection = isScrolling ? this.getScrollOffset() < offset ? "forward" : "backward" : null;
					this.scrollOffset = offset;
					this.isScrolling = isScrolling;
					if (this.scrollState) this.scheduleScrollReconcile();
					this.maybeNotify();
				}));
				this._scrollToOffset(this.getScrollOffset(), {
					adjustments: void 0,
					behavior: void 0
				});
			}
		};
		this.rafId = null;
		this.getSize = () => {
			if (!this.options.enabled) {
				this.scrollRect = null;
				return 0;
			}
			this.scrollRect = this.scrollRect ?? this.options.initialRect;
			return this.scrollRect[this.options.horizontal ? "width" : "height"];
		};
		this.getScrollOffset = () => {
			if (!this.options.enabled) {
				this.scrollOffset = null;
				return 0;
			}
			this.scrollOffset = this.scrollOffset ?? (typeof this.options.initialOffset === "function" ? this.options.initialOffset() : this.options.initialOffset);
			return this.scrollOffset;
		};
		this.getFurthestMeasurement = (measurements, index) => {
			const furthestMeasurementsFound = /* @__PURE__ */ new Map();
			const furthestMeasurements = /* @__PURE__ */ new Map();
			for (let m = index - 1; m >= 0; m--) {
				const measurement = measurements[m];
				if (furthestMeasurementsFound.has(measurement.lane)) continue;
				const previousFurthestMeasurement = furthestMeasurements.get(measurement.lane);
				if (previousFurthestMeasurement == null || measurement.end > previousFurthestMeasurement.end) furthestMeasurements.set(measurement.lane, measurement);
				else if (measurement.end < previousFurthestMeasurement.end) furthestMeasurementsFound.set(measurement.lane, true);
				if (furthestMeasurementsFound.size === this.options.lanes) break;
			}
			return furthestMeasurements.size === this.options.lanes ? Array.from(furthestMeasurements.values()).sort((a, b) => {
				if (a.end === b.end) return a.index - b.index;
				return a.end - b.end;
			})[0] : void 0;
		};
		this.getMeasurementOptions = memo(() => [
			this.options.count,
			this.options.paddingStart,
			this.options.scrollMargin,
			this.options.getItemKey,
			this.options.enabled,
			this.options.lanes
		], (count, paddingStart, scrollMargin, getItemKey, enabled, lanes) => {
			if (this.prevLanes !== void 0 && this.prevLanes !== lanes) this.lanesChangedFlag = true;
			this.prevLanes = lanes;
			this.pendingMeasuredCacheIndexes = [];
			return {
				count,
				paddingStart,
				scrollMargin,
				getItemKey,
				enabled,
				lanes
			};
		}, { key: false });
		this.getMeasurements = memo(() => [this.getMeasurementOptions(), this.itemSizeCache], ({ count, paddingStart, scrollMargin, getItemKey, enabled, lanes }, itemSizeCache) => {
			if (!enabled) {
				this.measurementsCache = [];
				this.itemSizeCache.clear();
				this.laneAssignments.clear();
				return [];
			}
			if (this.laneAssignments.size > count) {
				for (const index of this.laneAssignments.keys()) if (index >= count) this.laneAssignments.delete(index);
			}
			if (this.lanesChangedFlag) {
				this.lanesChangedFlag = false;
				this.lanesSettling = true;
				this.measurementsCache = [];
				this.itemSizeCache.clear();
				this.laneAssignments.clear();
				this.pendingMeasuredCacheIndexes = [];
			}
			if (this.measurementsCache.length === 0 && !this.lanesSettling) {
				this.measurementsCache = this.options.initialMeasurementsCache;
				this.measurementsCache.forEach((item) => {
					this.itemSizeCache.set(item.key, item.size);
				});
			}
			const min = this.lanesSettling ? 0 : this.pendingMeasuredCacheIndexes.length > 0 ? Math.min(...this.pendingMeasuredCacheIndexes) : 0;
			this.pendingMeasuredCacheIndexes = [];
			if (this.lanesSettling && this.measurementsCache.length === count) this.lanesSettling = false;
			const measurements = this.measurementsCache.slice(0, min);
			const laneLastIndex = new Array(lanes).fill(void 0);
			for (let m = 0; m < min; m++) {
				const item = measurements[m];
				if (item) laneLastIndex[item.lane] = m;
			}
			for (let i = min; i < count; i++) {
				const key = getItemKey(i);
				const cachedLane = this.laneAssignments.get(i);
				let lane;
				let start;
				if (cachedLane !== void 0 && this.options.lanes > 1) {
					lane = cachedLane;
					const prevIndex = laneLastIndex[lane];
					const prevInLane = prevIndex !== void 0 ? measurements[prevIndex] : void 0;
					start = prevInLane ? prevInLane.end + this.options.gap : paddingStart + scrollMargin;
				} else {
					const furthestMeasurement = this.options.lanes === 1 ? measurements[i - 1] : this.getFurthestMeasurement(measurements, i);
					start = furthestMeasurement ? furthestMeasurement.end + this.options.gap : paddingStart + scrollMargin;
					lane = furthestMeasurement ? furthestMeasurement.lane : i % this.options.lanes;
					if (this.options.lanes > 1) this.laneAssignments.set(i, lane);
				}
				const measuredSize = itemSizeCache.get(key);
				const size = typeof measuredSize === "number" ? measuredSize : this.options.estimateSize(i);
				const end = start + size;
				measurements[i] = {
					index: i,
					start,
					size,
					end,
					key,
					lane
				};
				laneLastIndex[lane] = i;
			}
			this.measurementsCache = measurements;
			return measurements;
		}, {
			key: false,
			debug: () => this.options.debug
		});
		this.calculateRange = memo(() => [
			this.getMeasurements(),
			this.getSize(),
			this.getScrollOffset(),
			this.options.lanes
		], (measurements, outerSize, scrollOffset, lanes) => {
			return this.range = measurements.length > 0 && outerSize > 0 ? calculateRange({
				measurements,
				outerSize,
				scrollOffset,
				lanes
			}) : null;
		}, {
			key: false,
			debug: () => this.options.debug
		});
		this.getVirtualIndexes = memo(() => {
			let startIndex = null;
			let endIndex = null;
			const range = this.calculateRange();
			if (range) {
				startIndex = range.startIndex;
				endIndex = range.endIndex;
			}
			this.maybeNotify.updateDeps([
				this.isScrolling,
				startIndex,
				endIndex
			]);
			return [
				this.options.rangeExtractor,
				this.options.overscan,
				this.options.count,
				startIndex,
				endIndex
			];
		}, (rangeExtractor, overscan, count, startIndex, endIndex) => {
			return startIndex === null || endIndex === null ? [] : rangeExtractor({
				startIndex,
				endIndex,
				overscan,
				count
			});
		}, {
			key: false,
			debug: () => this.options.debug
		});
		this.indexFromElement = (node) => {
			const attributeName = this.options.indexAttribute;
			const indexStr = node.getAttribute(attributeName);
			if (!indexStr) {
				console.warn(`Missing attribute name '${attributeName}={index}' on measured element.`);
				return -1;
			}
			return parseInt(indexStr, 10);
		};
		this.shouldMeasureDuringScroll = (index) => {
			var _a;
			if (!this.scrollState || this.scrollState.behavior !== "smooth") return true;
			const scrollIndex = this.scrollState.index ?? ((_a = this.getVirtualItemForOffset(this.scrollState.lastTargetOffset)) == null ? void 0 : _a.index);
			if (scrollIndex !== void 0 && this.range) {
				const bufferSize = Math.max(this.options.overscan, Math.ceil((this.range.endIndex - this.range.startIndex) / 2));
				const minIndex = Math.max(0, scrollIndex - bufferSize);
				const maxIndex = Math.min(this.options.count - 1, scrollIndex + bufferSize);
				return index >= minIndex && index <= maxIndex;
			}
			return true;
		};
		this.measureElement = (node) => {
			if (!node) {
				this.elementsCache.forEach((cached, key2) => {
					if (!cached.isConnected) {
						this.observer.unobserve(cached);
						this.elementsCache.delete(key2);
					}
				});
				return;
			}
			const index = this.indexFromElement(node);
			const key = this.options.getItemKey(index);
			const prevNode = this.elementsCache.get(key);
			if (prevNode !== node) {
				if (prevNode) this.observer.unobserve(prevNode);
				this.observer.observe(node);
				this.elementsCache.set(key, node);
			}
			if ((!this.isScrolling || this.scrollState) && this.shouldMeasureDuringScroll(index)) this.resizeItem(index, this.options.measureElement(node, void 0, this));
		};
		this.resizeItem = (index, size) => {
			var _a;
			const item = this.measurementsCache[index];
			if (!item) return;
			const delta = size - (this.itemSizeCache.get(item.key) ?? item.size);
			if (delta !== 0) {
				if (((_a = this.scrollState) == null ? void 0 : _a.behavior) !== "smooth" && (this.shouldAdjustScrollPositionOnItemSizeChange !== void 0 ? this.shouldAdjustScrollPositionOnItemSizeChange(item, delta, this) : item.start < this.getScrollOffset() + this.scrollAdjustments)) this._scrollToOffset(this.getScrollOffset(), {
					adjustments: this.scrollAdjustments += delta,
					behavior: void 0
				});
				this.pendingMeasuredCacheIndexes.push(item.index);
				this.itemSizeCache = new Map(this.itemSizeCache.set(item.key, size));
				this.notify(false);
			}
		};
		this.getVirtualItems = memo(() => [this.getVirtualIndexes(), this.getMeasurements()], (indexes, measurements) => {
			const virtualItems = [];
			for (let k = 0, len = indexes.length; k < len; k++) {
				const measurement = measurements[indexes[k]];
				virtualItems.push(measurement);
			}
			return virtualItems;
		}, {
			key: false,
			debug: () => this.options.debug
		});
		this.getVirtualItemForOffset = (offset) => {
			const measurements = this.getMeasurements();
			if (measurements.length === 0) return;
			return notUndefined(measurements[findNearestBinarySearch(0, measurements.length - 1, (index) => notUndefined(measurements[index]).start, offset)]);
		};
		this.getMaxScrollOffset = () => {
			if (!this.scrollElement) return 0;
			if ("scrollHeight" in this.scrollElement) return this.options.horizontal ? this.scrollElement.scrollWidth - this.scrollElement.clientWidth : this.scrollElement.scrollHeight - this.scrollElement.clientHeight;
			else {
				const doc = this.scrollElement.document.documentElement;
				return this.options.horizontal ? doc.scrollWidth - this.scrollElement.innerWidth : doc.scrollHeight - this.scrollElement.innerHeight;
			}
		};
		this.getOffsetForAlignment = (toOffset, align, itemSize = 0) => {
			if (!this.scrollElement) return 0;
			const size = this.getSize();
			const scrollOffset = this.getScrollOffset();
			if (align === "auto") align = toOffset >= scrollOffset + size ? "end" : "start";
			if (align === "center") toOffset += (itemSize - size) / 2;
			else if (align === "end") toOffset -= size;
			const maxOffset = this.getMaxScrollOffset();
			return Math.max(Math.min(maxOffset, toOffset), 0);
		};
		this.getOffsetForIndex = (index, align = "auto") => {
			index = Math.max(0, Math.min(index, this.options.count - 1));
			const size = this.getSize();
			const scrollOffset = this.getScrollOffset();
			const item = this.measurementsCache[index];
			if (!item) return;
			if (align === "auto") if (item.end >= scrollOffset + size - this.options.scrollPaddingEnd) align = "end";
			else if (item.start <= scrollOffset + this.options.scrollPaddingStart) align = "start";
			else return [scrollOffset, align];
			if (align === "end" && index === this.options.count - 1) return [this.getMaxScrollOffset(), align];
			const toOffset = align === "end" ? item.end + this.options.scrollPaddingEnd : item.start - this.options.scrollPaddingStart;
			return [this.getOffsetForAlignment(toOffset, align, item.size), align];
		};
		this.scrollToOffset = (toOffset, { align = "start", behavior = "auto" } = {}) => {
			const offset = this.getOffsetForAlignment(toOffset, align);
			this.scrollState = {
				index: null,
				align,
				behavior,
				startedAt: this.now(),
				lastTargetOffset: offset,
				stableFrames: 0
			};
			this._scrollToOffset(offset, {
				adjustments: void 0,
				behavior
			});
			this.scheduleScrollReconcile();
		};
		this.scrollToIndex = (index, { align: initialAlign = "auto", behavior = "auto" } = {}) => {
			index = Math.max(0, Math.min(index, this.options.count - 1));
			const offsetInfo = this.getOffsetForIndex(index, initialAlign);
			if (!offsetInfo) return;
			const [offset, align] = offsetInfo;
			const now = this.now();
			this.scrollState = {
				index,
				align,
				behavior,
				startedAt: now,
				lastTargetOffset: offset,
				stableFrames: 0
			};
			this._scrollToOffset(offset, {
				adjustments: void 0,
				behavior
			});
			this.scheduleScrollReconcile();
		};
		this.scrollBy = (delta, { behavior = "auto" } = {}) => {
			const offset = this.getScrollOffset() + delta;
			this.scrollState = {
				index: null,
				align: "start",
				behavior,
				startedAt: this.now(),
				lastTargetOffset: offset,
				stableFrames: 0
			};
			this._scrollToOffset(offset, {
				adjustments: void 0,
				behavior
			});
			this.scheduleScrollReconcile();
		};
		this.getTotalSize = () => {
			var _a;
			const measurements = this.getMeasurements();
			let end;
			if (measurements.length === 0) end = this.options.paddingStart;
			else if (this.options.lanes === 1) end = ((_a = measurements[measurements.length - 1]) == null ? void 0 : _a.end) ?? 0;
			else {
				const endByLane = Array(this.options.lanes).fill(null);
				let endIndex = measurements.length - 1;
				while (endIndex >= 0 && endByLane.some((val) => val === null)) {
					const item = measurements[endIndex];
					if (endByLane[item.lane] === null) endByLane[item.lane] = item.end;
					endIndex--;
				}
				end = Math.max(...endByLane.filter((val) => val !== null));
			}
			return Math.max(end - this.options.scrollMargin + this.options.paddingEnd, 0);
		};
		this._scrollToOffset = (offset, { adjustments, behavior }) => {
			this.options.scrollToFn(offset, {
				behavior,
				adjustments
			}, this);
		};
		this.measure = () => {
			this.itemSizeCache = /* @__PURE__ */ new Map();
			this.laneAssignments = /* @__PURE__ */ new Map();
			this.notify(false);
		};
		this.setOptions(opts);
	}
	scheduleScrollReconcile() {
		if (!this.targetWindow) {
			this.scrollState = null;
			return;
		}
		if (this.rafId != null) return;
		this.rafId = this.targetWindow.requestAnimationFrame(() => {
			this.rafId = null;
			this.reconcileScroll();
		});
	}
	reconcileScroll() {
		if (!this.scrollState) return;
		if (!this.scrollElement) return;
		if (this.now() - this.scrollState.startedAt > 5e3) {
			this.scrollState = null;
			return;
		}
		const offsetInfo = this.scrollState.index != null ? this.getOffsetForIndex(this.scrollState.index, this.scrollState.align) : void 0;
		const targetOffset = offsetInfo ? offsetInfo[0] : this.scrollState.lastTargetOffset;
		const STABLE_FRAMES = 1;
		const targetChanged = targetOffset !== this.scrollState.lastTargetOffset;
		if (!targetChanged && approxEqual(targetOffset, this.getScrollOffset())) {
			this.scrollState.stableFrames++;
			if (this.scrollState.stableFrames >= STABLE_FRAMES) {
				this.scrollState = null;
				return;
			}
		} else {
			this.scrollState.stableFrames = 0;
			if (targetChanged) {
				this.scrollState.lastTargetOffset = targetOffset;
				this.scrollState.behavior = "auto";
				this._scrollToOffset(targetOffset, {
					adjustments: void 0,
					behavior: "auto"
				});
			}
		}
		this.scheduleScrollReconcile();
	}
};
var findNearestBinarySearch = (low, high, getCurrentValue, value) => {
	while (low <= high) {
		const middle = (low + high) / 2 | 0;
		const currentValue = getCurrentValue(middle);
		if (currentValue < value) low = middle + 1;
		else if (currentValue > value) high = middle - 1;
		else return middle;
	}
	if (low > 0) return low - 1;
	else return 0;
};
function calculateRange({ measurements, outerSize, scrollOffset, lanes }) {
	const lastIndex = measurements.length - 1;
	const getOffset = (index) => measurements[index].start;
	if (measurements.length <= lanes) return {
		startIndex: 0,
		endIndex: lastIndex
	};
	let startIndex = findNearestBinarySearch(0, lastIndex, getOffset, scrollOffset);
	let endIndex = startIndex;
	if (lanes === 1) while (endIndex < lastIndex && measurements[endIndex].end < scrollOffset + outerSize) endIndex++;
	else if (lanes > 1) {
		const endPerLane = Array(lanes).fill(0);
		while (endIndex < lastIndex && endPerLane.some((pos) => pos < scrollOffset + outerSize)) {
			const item = measurements[endIndex];
			endPerLane[item.lane] = item.end;
			endIndex++;
		}
		const startPerLane = Array(lanes).fill(scrollOffset + outerSize);
		while (startIndex >= 0 && startPerLane.some((pos) => pos >= scrollOffset)) {
			const item = measurements[startIndex];
			startPerLane[item.lane] = item.start;
			startIndex--;
		}
		startIndex = Math.max(0, startIndex - startIndex % lanes);
		endIndex = Math.min(lastIndex, endIndex + (lanes - 1 - endIndex % lanes));
	}
	return {
		startIndex,
		endIndex
	};
}

//#endregion
//#region node_modules/mdurl/lib/decode.mjs
var decodeCache = {};
function getDecodeCache(exclude) {
	let cache = decodeCache[exclude];
	if (cache) return cache;
	cache = decodeCache[exclude] = [];
	for (let i = 0; i < 128; i++) {
		const ch = String.fromCharCode(i);
		cache.push(ch);
	}
	for (let i = 0; i < exclude.length; i++) {
		const ch = exclude.charCodeAt(i);
		cache[ch] = "%" + ("0" + ch.toString(16).toUpperCase()).slice(-2);
	}
	return cache;
}
function decode$1(string, exclude) {
	if (typeof exclude !== "string") exclude = decode$1.defaultChars;
	const cache = getDecodeCache(exclude);
	return string.replace(/(%[a-f0-9]{2})+/gi, function(seq) {
		let result = "";
		for (let i = 0, l = seq.length; i < l; i += 3) {
			const b1 = parseInt(seq.slice(i + 1, i + 3), 16);
			if (b1 < 128) {
				result += cache[b1];
				continue;
			}
			if ((b1 & 224) === 192 && i + 3 < l) {
				const b2 = parseInt(seq.slice(i + 4, i + 6), 16);
				if ((b2 & 192) === 128) {
					const chr = b1 << 6 & 1984 | b2 & 63;
					if (chr < 128) result += "��";
					else result += String.fromCharCode(chr);
					i += 3;
					continue;
				}
			}
			if ((b1 & 240) === 224 && i + 6 < l) {
				const b2 = parseInt(seq.slice(i + 4, i + 6), 16);
				const b3 = parseInt(seq.slice(i + 7, i + 9), 16);
				if ((b2 & 192) === 128 && (b3 & 192) === 128) {
					const chr = b1 << 12 & 61440 | b2 << 6 & 4032 | b3 & 63;
					if (chr < 2048 || chr >= 55296 && chr <= 57343) result += "���";
					else result += String.fromCharCode(chr);
					i += 6;
					continue;
				}
			}
			if ((b1 & 248) === 240 && i + 9 < l) {
				const b2 = parseInt(seq.slice(i + 4, i + 6), 16);
				const b3 = parseInt(seq.slice(i + 7, i + 9), 16);
				const b4 = parseInt(seq.slice(i + 10, i + 12), 16);
				if ((b2 & 192) === 128 && (b3 & 192) === 128 && (b4 & 192) === 128) {
					let chr = b1 << 18 & 1835008 | b2 << 12 & 258048 | b3 << 6 & 4032 | b4 & 63;
					if (chr < 65536 || chr > 1114111) result += "����";
					else {
						chr -= 65536;
						result += String.fromCharCode(55296 + (chr >> 10), 56320 + (chr & 1023));
					}
					i += 9;
					continue;
				}
			}
			result += "�";
		}
		return result;
	});
}
decode$1.defaultChars = ";/?:@&=+$,#";
decode$1.componentChars = "";
var decode_default = decode$1;

//#endregion
//#region node_modules/mdurl/lib/encode.mjs
var encodeCache = {};
function getEncodeCache(exclude) {
	let cache = encodeCache[exclude];
	if (cache) return cache;
	cache = encodeCache[exclude] = [];
	for (let i = 0; i < 128; i++) {
		const ch = String.fromCharCode(i);
		if (/^[0-9a-z]$/i.test(ch)) cache.push(ch);
		else cache.push("%" + ("0" + i.toString(16).toUpperCase()).slice(-2));
	}
	for (let i = 0; i < exclude.length; i++) cache[exclude.charCodeAt(i)] = exclude[i];
	return cache;
}
function encode$1(string, exclude, keepEscaped) {
	if (typeof exclude !== "string") {
		keepEscaped = exclude;
		exclude = encode$1.defaultChars;
	}
	if (typeof keepEscaped === "undefined") keepEscaped = true;
	const cache = getEncodeCache(exclude);
	let result = "";
	for (let i = 0, l = string.length; i < l; i++) {
		const code = string.charCodeAt(i);
		if (keepEscaped && code === 37 && i + 2 < l) {
			if (/^[0-9a-f]{2}$/i.test(string.slice(i + 1, i + 3))) {
				result += string.slice(i, i + 3);
				i += 2;
				continue;
			}
		}
		if (code < 128) {
			result += cache[code];
			continue;
		}
		if (code >= 55296 && code <= 57343) {
			if (code >= 55296 && code <= 56319 && i + 1 < l) {
				const nextCode = string.charCodeAt(i + 1);
				if (nextCode >= 56320 && nextCode <= 57343) {
					result += encodeURIComponent(string[i] + string[i + 1]);
					i++;
					continue;
				}
			}
			result += "%EF%BF%BD";
			continue;
		}
		result += encodeURIComponent(string[i]);
	}
	return result;
}
encode$1.defaultChars = ";/?:@&=+$,-_.!~*'()#";
encode$1.componentChars = "-_.!~*'()";
var encode_default = encode$1;

//#endregion
//#region node_modules/mdurl/lib/format.mjs
function format(url) {
	let result = "";
	result += url.protocol || "";
	result += url.slashes ? "//" : "";
	result += url.auth ? url.auth + "@" : "";
	if (url.hostname && url.hostname.indexOf(":") !== -1) result += "[" + url.hostname + "]";
	else result += url.hostname || "";
	result += url.port ? ":" + url.port : "";
	result += url.pathname || "";
	result += url.search || "";
	result += url.hash || "";
	return result;
}

//#endregion
//#region node_modules/mdurl/lib/parse.mjs
function Url() {
	this.protocol = null;
	this.slashes = null;
	this.auth = null;
	this.port = null;
	this.hostname = null;
	this.hash = null;
	this.search = null;
	this.pathname = null;
}
var protocolPattern = /^([a-z0-9.+-]+:)/i;
var portPattern = /:[0-9]*$/;
var simplePathPattern = /^(\/\/?(?!\/)[^\?\s]*)(\?[^\s]*)?$/;
var unwise = [
	"{",
	"}",
	"|",
	"\\",
	"^",
	"`"
].concat([
	"<",
	">",
	"\"",
	"`",
	" ",
	"\r",
	"\n",
	"	"
]);
var autoEscape = ["'"].concat(unwise);
var nonHostChars = [
	"%",
	"/",
	"?",
	";",
	"#"
].concat(autoEscape);
var hostEndingChars = [
	"/",
	"?",
	"#"
];
var hostnameMaxLen = 255;
var hostnamePartPattern = /^[+a-z0-9A-Z_-]{0,63}$/;
var hostnamePartStart = /^([+a-z0-9A-Z_-]{0,63})(.*)$/;
var hostlessProtocol = {
	javascript: true,
	"javascript:": true
};
var slashedProtocol = {
	http: true,
	https: true,
	ftp: true,
	gopher: true,
	file: true,
	"http:": true,
	"https:": true,
	"ftp:": true,
	"gopher:": true,
	"file:": true
};
function urlParse(url, slashesDenoteHost) {
	if (url && url instanceof Url) return url;
	const u = new Url();
	u.parse(url, slashesDenoteHost);
	return u;
}
Url.prototype.parse = function(url, slashesDenoteHost) {
	let lowerProto, hec, slashes;
	let rest = url;
	rest = rest.trim();
	if (!slashesDenoteHost && url.split("#").length === 1) {
		const simplePath = simplePathPattern.exec(rest);
		if (simplePath) {
			this.pathname = simplePath[1];
			if (simplePath[2]) this.search = simplePath[2];
			return this;
		}
	}
	let proto = protocolPattern.exec(rest);
	if (proto) {
		proto = proto[0];
		lowerProto = proto.toLowerCase();
		this.protocol = proto;
		rest = rest.substr(proto.length);
	}
	if (slashesDenoteHost || proto || rest.match(/^\/\/[^@\/]+@[^@\/]+/)) {
		slashes = rest.substr(0, 2) === "//";
		if (slashes && !(proto && hostlessProtocol[proto])) {
			rest = rest.substr(2);
			this.slashes = true;
		}
	}
	if (!hostlessProtocol[proto] && (slashes || proto && !slashedProtocol[proto])) {
		let hostEnd = -1;
		for (let i = 0; i < hostEndingChars.length; i++) {
			hec = rest.indexOf(hostEndingChars[i]);
			if (hec !== -1 && (hostEnd === -1 || hec < hostEnd)) hostEnd = hec;
		}
		let auth, atSign;
		if (hostEnd === -1) atSign = rest.lastIndexOf("@");
		else atSign = rest.lastIndexOf("@", hostEnd);
		if (atSign !== -1) {
			auth = rest.slice(0, atSign);
			rest = rest.slice(atSign + 1);
			this.auth = auth;
		}
		hostEnd = -1;
		for (let i = 0; i < nonHostChars.length; i++) {
			hec = rest.indexOf(nonHostChars[i]);
			if (hec !== -1 && (hostEnd === -1 || hec < hostEnd)) hostEnd = hec;
		}
		if (hostEnd === -1) hostEnd = rest.length;
		if (rest[hostEnd - 1] === ":") hostEnd--;
		const host = rest.slice(0, hostEnd);
		rest = rest.slice(hostEnd);
		this.parseHost(host);
		this.hostname = this.hostname || "";
		const ipv6Hostname = this.hostname[0] === "[" && this.hostname[this.hostname.length - 1] === "]";
		if (!ipv6Hostname) {
			const hostparts = this.hostname.split(/\./);
			for (let i = 0, l = hostparts.length; i < l; i++) {
				const part = hostparts[i];
				if (!part) continue;
				if (!part.match(hostnamePartPattern)) {
					let newpart = "";
					for (let j = 0, k = part.length; j < k; j++) if (part.charCodeAt(j) > 127) newpart += "x";
					else newpart += part[j];
					if (!newpart.match(hostnamePartPattern)) {
						const validParts = hostparts.slice(0, i);
						const notHost = hostparts.slice(i + 1);
						const bit = part.match(hostnamePartStart);
						if (bit) {
							validParts.push(bit[1]);
							notHost.unshift(bit[2]);
						}
						if (notHost.length) rest = notHost.join(".") + rest;
						this.hostname = validParts.join(".");
						break;
					}
				}
			}
		}
		if (this.hostname.length > hostnameMaxLen) this.hostname = "";
		if (ipv6Hostname) this.hostname = this.hostname.substr(1, this.hostname.length - 2);
	}
	const hash = rest.indexOf("#");
	if (hash !== -1) {
		this.hash = rest.substr(hash);
		rest = rest.slice(0, hash);
	}
	const qm = rest.indexOf("?");
	if (qm !== -1) {
		this.search = rest.substr(qm);
		rest = rest.slice(0, qm);
	}
	if (rest) this.pathname = rest;
	if (slashedProtocol[lowerProto] && this.hostname && !this.pathname) this.pathname = "";
	return this;
};
Url.prototype.parseHost = function(host) {
	let port = portPattern.exec(host);
	if (port) {
		port = port[0];
		if (port !== ":") this.port = port.substr(1);
		host = host.substr(0, host.length - port.length);
	}
	if (host) this.hostname = host;
};
var parse_default = urlParse;

//#endregion
//#region node_modules/mdurl/index.mjs
var mdurl_exports = /* @__PURE__ */ __export({
	decode: () => decode_default,
	encode: () => encode_default,
	format: () => format,
	parse: () => parse_default
});

//#endregion
//#region node_modules/uc.micro/properties/Any/regex.mjs
var regex_default = /[\0-\uD7FF\uE000-\uFFFF]|[\uD800-\uDBFF][\uDC00-\uDFFF]|[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?:[^\uD800-\uDBFF]|^)[\uDC00-\uDFFF]/;

//#endregion
//#region node_modules/uc.micro/categories/Cc/regex.mjs
var regex_default$1 = /[\0-\x1F\x7F-\x9F]/;

//#endregion
//#region node_modules/uc.micro/categories/Cf/regex.mjs
var regex_default$4 = /[\xAD\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u180E\u200B-\u200F\u202A-\u202E\u2060-\u2064\u2066-\u206F\uFEFF\uFFF9-\uFFFB]|\uD804[\uDCBD\uDCCD]|\uD80D[\uDC30-\uDC3F]|\uD82F[\uDCA0-\uDCA3]|\uD834[\uDD73-\uDD7A]|\uDB40[\uDC01\uDC20-\uDC7F]/;

//#endregion
//#region node_modules/uc.micro/categories/P/regex.mjs
var regex_default$3 = /[!-#%-\*,-\/:;\?@\[-\]_\{\}\xA1\xA7\xAB\xB6\xB7\xBB\xBF\u037E\u0387\u055A-\u055F\u0589\u058A\u05BE\u05C0\u05C3\u05C6\u05F3\u05F4\u0609\u060A\u060C\u060D\u061B\u061D-\u061F\u066A-\u066D\u06D4\u0700-\u070D\u07F7-\u07F9\u0830-\u083E\u085E\u0964\u0965\u0970\u09FD\u0A76\u0AF0\u0C77\u0C84\u0DF4\u0E4F\u0E5A\u0E5B\u0F04-\u0F12\u0F14\u0F3A-\u0F3D\u0F85\u0FD0-\u0FD4\u0FD9\u0FDA\u104A-\u104F\u10FB\u1360-\u1368\u1400\u166E\u169B\u169C\u16EB-\u16ED\u1735\u1736\u17D4-\u17D6\u17D8-\u17DA\u1800-\u180A\u1944\u1945\u1A1E\u1A1F\u1AA0-\u1AA6\u1AA8-\u1AAD\u1B5A-\u1B60\u1B7D\u1B7E\u1BFC-\u1BFF\u1C3B-\u1C3F\u1C7E\u1C7F\u1CC0-\u1CC7\u1CD3\u2010-\u2027\u2030-\u2043\u2045-\u2051\u2053-\u205E\u207D\u207E\u208D\u208E\u2308-\u230B\u2329\u232A\u2768-\u2775\u27C5\u27C6\u27E6-\u27EF\u2983-\u2998\u29D8-\u29DB\u29FC\u29FD\u2CF9-\u2CFC\u2CFE\u2CFF\u2D70\u2E00-\u2E2E\u2E30-\u2E4F\u2E52-\u2E5D\u3001-\u3003\u3008-\u3011\u3014-\u301F\u3030\u303D\u30A0\u30FB\uA4FE\uA4FF\uA60D-\uA60F\uA673\uA67E\uA6F2-\uA6F7\uA874-\uA877\uA8CE\uA8CF\uA8F8-\uA8FA\uA8FC\uA92E\uA92F\uA95F\uA9C1-\uA9CD\uA9DE\uA9DF\uAA5C-\uAA5F\uAADE\uAADF\uAAF0\uAAF1\uABEB\uFD3E\uFD3F\uFE10-\uFE19\uFE30-\uFE52\uFE54-\uFE61\uFE63\uFE68\uFE6A\uFE6B\uFF01-\uFF03\uFF05-\uFF0A\uFF0C-\uFF0F\uFF1A\uFF1B\uFF1F\uFF20\uFF3B-\uFF3D\uFF3F\uFF5B\uFF5D\uFF5F-\uFF65]|\uD800[\uDD00-\uDD02\uDF9F\uDFD0]|\uD801\uDD6F|\uD802[\uDC57\uDD1F\uDD3F\uDE50-\uDE58\uDE7F\uDEF0-\uDEF6\uDF39-\uDF3F\uDF99-\uDF9C]|\uD803[\uDEAD\uDF55-\uDF59\uDF86-\uDF89]|\uD804[\uDC47-\uDC4D\uDCBB\uDCBC\uDCBE-\uDCC1\uDD40-\uDD43\uDD74\uDD75\uDDC5-\uDDC8\uDDCD\uDDDB\uDDDD-\uDDDF\uDE38-\uDE3D\uDEA9]|\uD805[\uDC4B-\uDC4F\uDC5A\uDC5B\uDC5D\uDCC6\uDDC1-\uDDD7\uDE41-\uDE43\uDE60-\uDE6C\uDEB9\uDF3C-\uDF3E]|\uD806[\uDC3B\uDD44-\uDD46\uDDE2\uDE3F-\uDE46\uDE9A-\uDE9C\uDE9E-\uDEA2\uDF00-\uDF09]|\uD807[\uDC41-\uDC45\uDC70\uDC71\uDEF7\uDEF8\uDF43-\uDF4F\uDFFF]|\uD809[\uDC70-\uDC74]|\uD80B[\uDFF1\uDFF2]|\uD81A[\uDE6E\uDE6F\uDEF5\uDF37-\uDF3B\uDF44]|\uD81B[\uDE97-\uDE9A\uDFE2]|\uD82F\uDC9F|\uD836[\uDE87-\uDE8B]|\uD83A[\uDD5E\uDD5F]/;

//#endregion
//#region node_modules/uc.micro/categories/S/regex.mjs
var regex_default$5 = /[\$\+<->\^`\|~\xA2-\xA6\xA8\xA9\xAC\xAE-\xB1\xB4\xB8\xD7\xF7\u02C2-\u02C5\u02D2-\u02DF\u02E5-\u02EB\u02ED\u02EF-\u02FF\u0375\u0384\u0385\u03F6\u0482\u058D-\u058F\u0606-\u0608\u060B\u060E\u060F\u06DE\u06E9\u06FD\u06FE\u07F6\u07FE\u07FF\u0888\u09F2\u09F3\u09FA\u09FB\u0AF1\u0B70\u0BF3-\u0BFA\u0C7F\u0D4F\u0D79\u0E3F\u0F01-\u0F03\u0F13\u0F15-\u0F17\u0F1A-\u0F1F\u0F34\u0F36\u0F38\u0FBE-\u0FC5\u0FC7-\u0FCC\u0FCE\u0FCF\u0FD5-\u0FD8\u109E\u109F\u1390-\u1399\u166D\u17DB\u1940\u19DE-\u19FF\u1B61-\u1B6A\u1B74-\u1B7C\u1FBD\u1FBF-\u1FC1\u1FCD-\u1FCF\u1FDD-\u1FDF\u1FED-\u1FEF\u1FFD\u1FFE\u2044\u2052\u207A-\u207C\u208A-\u208C\u20A0-\u20C0\u2100\u2101\u2103-\u2106\u2108\u2109\u2114\u2116-\u2118\u211E-\u2123\u2125\u2127\u2129\u212E\u213A\u213B\u2140-\u2144\u214A-\u214D\u214F\u218A\u218B\u2190-\u2307\u230C-\u2328\u232B-\u2426\u2440-\u244A\u249C-\u24E9\u2500-\u2767\u2794-\u27C4\u27C7-\u27E5\u27F0-\u2982\u2999-\u29D7\u29DC-\u29FB\u29FE-\u2B73\u2B76-\u2B95\u2B97-\u2BFF\u2CE5-\u2CEA\u2E50\u2E51\u2E80-\u2E99\u2E9B-\u2EF3\u2F00-\u2FD5\u2FF0-\u2FFF\u3004\u3012\u3013\u3020\u3036\u3037\u303E\u303F\u309B\u309C\u3190\u3191\u3196-\u319F\u31C0-\u31E3\u31EF\u3200-\u321E\u322A-\u3247\u3250\u3260-\u327F\u328A-\u32B0\u32C0-\u33FF\u4DC0-\u4DFF\uA490-\uA4C6\uA700-\uA716\uA720\uA721\uA789\uA78A\uA828-\uA82B\uA836-\uA839\uAA77-\uAA79\uAB5B\uAB6A\uAB6B\uFB29\uFBB2-\uFBC2\uFD40-\uFD4F\uFDCF\uFDFC-\uFDFF\uFE62\uFE64-\uFE66\uFE69\uFF04\uFF0B\uFF1C-\uFF1E\uFF3E\uFF40\uFF5C\uFF5E\uFFE0-\uFFE6\uFFE8-\uFFEE\uFFFC\uFFFD]|\uD800[\uDD37-\uDD3F\uDD79-\uDD89\uDD8C-\uDD8E\uDD90-\uDD9C\uDDA0\uDDD0-\uDDFC]|\uD802[\uDC77\uDC78\uDEC8]|\uD805\uDF3F|\uD807[\uDFD5-\uDFF1]|\uD81A[\uDF3C-\uDF3F\uDF45]|\uD82F\uDC9C|\uD833[\uDF50-\uDFC3]|\uD834[\uDC00-\uDCF5\uDD00-\uDD26\uDD29-\uDD64\uDD6A-\uDD6C\uDD83\uDD84\uDD8C-\uDDA9\uDDAE-\uDDEA\uDE00-\uDE41\uDE45\uDF00-\uDF56]|\uD835[\uDEC1\uDEDB\uDEFB\uDF15\uDF35\uDF4F\uDF6F\uDF89\uDFA9\uDFC3]|\uD836[\uDC00-\uDDFF\uDE37-\uDE3A\uDE6D-\uDE74\uDE76-\uDE83\uDE85\uDE86]|\uD838[\uDD4F\uDEFF]|\uD83B[\uDCAC\uDCB0\uDD2E\uDEF0\uDEF1]|\uD83C[\uDC00-\uDC2B\uDC30-\uDC93\uDCA0-\uDCAE\uDCB1-\uDCBF\uDCC1-\uDCCF\uDCD1-\uDCF5\uDD0D-\uDDAD\uDDE6-\uDE02\uDE10-\uDE3B\uDE40-\uDE48\uDE50\uDE51\uDE60-\uDE65\uDF00-\uDFFF]|\uD83D[\uDC00-\uDED7\uDEDC-\uDEEC\uDEF0-\uDEFC\uDF00-\uDF76\uDF7B-\uDFD9\uDFE0-\uDFEB\uDFF0]|\uD83E[\uDC00-\uDC0B\uDC10-\uDC47\uDC50-\uDC59\uDC60-\uDC87\uDC90-\uDCAD\uDCB0\uDCB1\uDD00-\uDE53\uDE60-\uDE6D\uDE70-\uDE7C\uDE80-\uDE88\uDE90-\uDEBD\uDEBF-\uDEC5\uDECE-\uDEDB\uDEE0-\uDEE8\uDEF0-\uDEF8\uDF00-\uDF92\uDF94-\uDFCA]/;

//#endregion
//#region node_modules/uc.micro/categories/Z/regex.mjs
var regex_default$2 = /[ \xA0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]/;

//#endregion
//#region node_modules/uc.micro/index.mjs
var uc_exports = /* @__PURE__ */ __export({
	Any: () => regex_default,
	Cc: () => regex_default$1,
	Cf: () => regex_default$4,
	P: () => regex_default$3,
	S: () => regex_default$5,
	Z: () => regex_default$2
});

//#endregion
//#region node_modules/linkify-it/lib/re.mjs
function re_default(opts) {
	const re = {};
	opts = opts || {};
	re.src_Any = regex_default.source;
	re.src_Cc = regex_default$1.source;
	re.src_Z = regex_default$2.source;
	re.src_P = regex_default$3.source;
	re.src_ZPCc = [
		re.src_Z,
		re.src_P,
		re.src_Cc
	].join("|");
	re.src_ZCc = [re.src_Z, re.src_Cc].join("|");
	const text_separators = "[><｜]";
	re.src_pseudo_letter = "(?:(?!" + text_separators + "|" + re.src_ZPCc + ")" + re.src_Any + ")";
	re.src_ip4 = "(?:(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)";
	re.src_auth = "(?:(?:(?!" + re.src_ZCc + "|[@/\\[\\]()]).)+@)?";
	re.src_port = "(?::(?:6(?:[0-4]\\d{3}|5(?:[0-4]\\d{2}|5(?:[0-2]\\d|3[0-5])))|[1-5]?\\d{1,4}))?";
	re.src_host_terminator = "(?=$|" + text_separators + "|" + re.src_ZPCc + ")(?!" + (opts["---"] ? "-(?!--)|" : "-|") + "_|:\\d|\\.-|\\.(?!$|" + re.src_ZPCc + "))";
	re.src_path = "(?:[/?#](?:(?!" + re.src_ZCc + "|[><｜]|[()[\\]{}.,\"'?!\\-;]).|\\[(?:(?!" + re.src_ZCc + "|\\]).)*\\]|\\((?:(?!" + re.src_ZCc + "|[)]).)*\\)|\\{(?:(?!" + re.src_ZCc + "|[}]).)*\\}|\\\"(?:(?!" + re.src_ZCc + "|[\"]).)+\\\"|\\'(?:(?!" + re.src_ZCc + "|[']).)+\\'|\\'(?=" + re.src_pseudo_letter + "|[-])|\\.{2,}[a-zA-Z0-9%/&]|\\.(?!" + re.src_ZCc + "|[.]|$)|" + (opts["---"] ? "\\-(?!--(?:[^-]|$))(?:-*)|" : "\\-+|") + ",(?!" + re.src_ZCc + "|$)|;(?!" + re.src_ZCc + "|$)|\\!+(?!" + re.src_ZCc + "|[!]|$)|\\?(?!" + re.src_ZCc + "|[?]|$))+|\\/)?";
	re.src_email_name = "[\\-;:&=\\+\\$,\\.a-zA-Z0-9_][\\-;:&=\\+\\$,\\\"\\.a-zA-Z0-9_]*";
	re.src_xn = "xn--[a-z0-9\\-]{1,59}";
	re.src_domain_root = "(?:" + re.src_xn + "|" + re.src_pseudo_letter + "{1,63})";
	re.src_domain = "(?:" + re.src_xn + "|(?:" + re.src_pseudo_letter + ")|(?:" + re.src_pseudo_letter + "(?:-|" + re.src_pseudo_letter + "){0,61}" + re.src_pseudo_letter + "))";
	re.src_host = "(?:(?:(?:(?:" + re.src_domain + ")\\.)*" + re.src_domain + "))";
	re.tpl_host_fuzzy = "(?:" + re.src_ip4 + "|(?:(?:(?:" + re.src_domain + ")\\.)+(?:%TLDS%)))";
	re.tpl_host_no_ip_fuzzy = "(?:(?:(?:" + re.src_domain + ")\\.)+(?:%TLDS%))";
	re.src_host_strict = re.src_host + re.src_host_terminator;
	re.tpl_host_fuzzy_strict = re.tpl_host_fuzzy + re.src_host_terminator;
	re.src_host_port_strict = re.src_host + re.src_port + re.src_host_terminator;
	re.tpl_host_port_fuzzy_strict = re.tpl_host_fuzzy + re.src_port + re.src_host_terminator;
	re.tpl_host_port_no_ip_fuzzy_strict = re.tpl_host_no_ip_fuzzy + re.src_port + re.src_host_terminator;
	re.tpl_host_fuzzy_test = "localhost|www\\.|\\.\\d{1,3}\\.|(?:\\.(?:%TLDS%)(?:" + re.src_ZPCc + "|>|$))";
	re.tpl_email_fuzzy = "(^|" + text_separators + "|\"|\\(|" + re.src_ZCc + ")(" + re.src_email_name + "@" + re.tpl_host_fuzzy_strict + ")";
	re.tpl_link_fuzzy = "(^|(?![.:/\\-_@])(?:[$+<=>^`|｜]|" + re.src_ZPCc + "))((?![$+<=>^`|｜])" + re.tpl_host_port_fuzzy_strict + re.src_path + ")";
	re.tpl_link_no_ip_fuzzy = "(^|(?![.:/\\-_@])(?:[$+<=>^`|｜]|" + re.src_ZPCc + "))((?![$+<=>^`|｜])" + re.tpl_host_port_no_ip_fuzzy_strict + re.src_path + ")";
	return re;
}

//#endregion
//#region node_modules/linkify-it/index.mjs
function assign(obj) {
	Array.prototype.slice.call(arguments, 1).forEach(function(source) {
		if (!source) return;
		Object.keys(source).forEach(function(key) {
			obj[key] = source[key];
		});
	});
	return obj;
}
function _class(obj) {
	return Object.prototype.toString.call(obj);
}
function isString(obj) {
	return _class(obj) === "[object String]";
}
function isObject(obj) {
	return _class(obj) === "[object Object]";
}
function isRegExp(obj) {
	return _class(obj) === "[object RegExp]";
}
function isFunction(obj) {
	return _class(obj) === "[object Function]";
}
function escapeRE(str) {
	return str.replace(/[.?*+^$[\]\\(){}|-]/g, "\\$&");
}
var defaultOptions = {
	fuzzyLink: true,
	fuzzyEmail: true,
	fuzzyIP: false
};
function isOptionsObj(obj) {
	return Object.keys(obj || {}).reduce(function(acc, k) {
		return acc || defaultOptions.hasOwnProperty(k);
	}, false);
}
var defaultSchemas = {
	"http:": { validate: function(text$1, pos, self) {
		const tail = text$1.slice(pos);
		if (!self.re.http) self.re.http = new RegExp("^\\/\\/" + self.re.src_auth + self.re.src_host_port_strict + self.re.src_path, "i");
		if (self.re.http.test(tail)) return tail.match(self.re.http)[0].length;
		return 0;
	} },
	"https:": "http:",
	"ftp:": "http:",
	"//": { validate: function(text$1, pos, self) {
		const tail = text$1.slice(pos);
		if (!self.re.no_http) self.re.no_http = new RegExp("^" + self.re.src_auth + "(?:localhost|(?:(?:" + self.re.src_domain + ")\\.)+" + self.re.src_domain_root + ")" + self.re.src_port + self.re.src_host_terminator + self.re.src_path, "i");
		if (self.re.no_http.test(tail)) {
			if (pos >= 3 && text$1[pos - 3] === ":") return 0;
			if (pos >= 3 && text$1[pos - 3] === "/") return 0;
			return tail.match(self.re.no_http)[0].length;
		}
		return 0;
	} },
	"mailto:": { validate: function(text$1, pos, self) {
		const tail = text$1.slice(pos);
		if (!self.re.mailto) self.re.mailto = new RegExp("^" + self.re.src_email_name + "@" + self.re.src_host_strict, "i");
		if (self.re.mailto.test(tail)) return tail.match(self.re.mailto)[0].length;
		return 0;
	} }
};
var tlds_2ch_src_re = "a[cdefgilmnoqrstuwxz]|b[abdefghijmnorstvwyz]|c[acdfghiklmnoruvwxyz]|d[ejkmoz]|e[cegrstu]|f[ijkmor]|g[abdefghilmnpqrstuwy]|h[kmnrtu]|i[delmnoqrst]|j[emop]|k[eghimnprwyz]|l[abcikrstuvy]|m[acdeghklmnopqrstuvwxyz]|n[acefgilopruz]|om|p[aefghklmnrstwy]|qa|r[eosuw]|s[abcdeghijklmnortuvxyz]|t[cdfghjklmnortvwz]|u[agksyz]|v[aceginu]|w[fs]|y[et]|z[amw]";
var tlds_default = "biz|com|edu|gov|net|org|pro|web|xxx|aero|asia|coop|info|museum|name|shop|рф".split("|");
function resetScanCache(self) {
	self.__index__ = -1;
	self.__text_cache__ = "";
}
function createValidator(re) {
	return function(text$1, pos) {
		const tail = text$1.slice(pos);
		if (re.test(tail)) return tail.match(re)[0].length;
		return 0;
	};
}
function createNormalizer() {
	return function(match, self) {
		self.normalize(match);
	};
}
function compile(self) {
	const re = self.re = re_default(self.__opts__);
	const tlds = self.__tlds__.slice();
	self.onCompile();
	if (!self.__tlds_replaced__) tlds.push(tlds_2ch_src_re);
	tlds.push(re.src_xn);
	re.src_tlds = tlds.join("|");
	function untpl(tpl) {
		return tpl.replace("%TLDS%", re.src_tlds);
	}
	re.email_fuzzy = RegExp(untpl(re.tpl_email_fuzzy), "i");
	re.link_fuzzy = RegExp(untpl(re.tpl_link_fuzzy), "i");
	re.link_no_ip_fuzzy = RegExp(untpl(re.tpl_link_no_ip_fuzzy), "i");
	re.host_fuzzy_test = RegExp(untpl(re.tpl_host_fuzzy_test), "i");
	const aliases = [];
	self.__compiled__ = {};
	function schemaError(name, val) {
		throw new Error("(LinkifyIt) Invalid schema \"" + name + "\": " + val);
	}
	Object.keys(self.__schemas__).forEach(function(name) {
		const val = self.__schemas__[name];
		if (val === null) return;
		const compiled = {
			validate: null,
			link: null
		};
		self.__compiled__[name] = compiled;
		if (isObject(val)) {
			if (isRegExp(val.validate)) compiled.validate = createValidator(val.validate);
			else if (isFunction(val.validate)) compiled.validate = val.validate;
			else schemaError(name, val);
			if (isFunction(val.normalize)) compiled.normalize = val.normalize;
			else if (!val.normalize) compiled.normalize = createNormalizer();
			else schemaError(name, val);
			return;
		}
		if (isString(val)) {
			aliases.push(name);
			return;
		}
		schemaError(name, val);
	});
	aliases.forEach(function(alias) {
		if (!self.__compiled__[self.__schemas__[alias]]) return;
		self.__compiled__[alias].validate = self.__compiled__[self.__schemas__[alias]].validate;
		self.__compiled__[alias].normalize = self.__compiled__[self.__schemas__[alias]].normalize;
	});
	self.__compiled__[""] = {
		validate: null,
		normalize: createNormalizer()
	};
	const slist = Object.keys(self.__compiled__).filter(function(name) {
		return name.length > 0 && self.__compiled__[name];
	}).map(escapeRE).join("|");
	self.re.schema_test = RegExp("(^|(?!_)(?:[><｜]|" + re.src_ZPCc + "))(" + slist + ")", "i");
	self.re.schema_search = RegExp("(^|(?!_)(?:[><｜]|" + re.src_ZPCc + "))(" + slist + ")", "ig");
	self.re.schema_at_start = RegExp("^" + self.re.schema_search.source, "i");
	self.re.pretest = RegExp("(" + self.re.schema_test.source + ")|(" + self.re.host_fuzzy_test.source + ")|@", "i");
	resetScanCache(self);
}
/**
* class Match
*
* Match result. Single element of array, returned by [[LinkifyIt#match]]
**/
function Match(self, shift) {
	const start = self.__index__;
	const end = self.__last_index__;
	const text$1 = self.__text_cache__.slice(start, end);
	/**
	* Match#schema -> String
	*
	* Prefix (protocol) for matched string.
	**/
	this.schema = self.__schema__.toLowerCase();
	/**
	* Match#index -> Number
	*
	* First position of matched string.
	**/
	this.index = start + shift;
	/**
	* Match#lastIndex -> Number
	*
	* Next position after matched string.
	**/
	this.lastIndex = end + shift;
	/**
	* Match#raw -> String
	*
	* Matched string.
	**/
	this.raw = text$1;
	/**
	* Match#text -> String
	*
	* Notmalized text of matched string.
	**/
	this.text = text$1;
	/**
	* Match#url -> String
	*
	* Normalized url of matched string.
	**/
	this.url = text$1;
}
function createMatch(self, shift) {
	const match = new Match(self, shift);
	self.__compiled__[match.schema].normalize(match, self);
	return match;
}
/**
* class LinkifyIt
**/
/**
* new LinkifyIt(schemas, options)
* - schemas (Object): Optional. Additional schemas to validate (prefix/validator)
* - options (Object): { fuzzyLink|fuzzyEmail|fuzzyIP: true|false }
*
* Creates new linkifier instance with optional additional schemas.
* Can be called without `new` keyword for convenience.
*
* By default understands:
*
* - `http(s)://...` , `ftp://...`, `mailto:...` & `//...` links
* - "fuzzy" links and emails (example.com, foo@bar.com).
*
* `schemas` is an object, where each key/value describes protocol/rule:
*
* - __key__ - link prefix (usually, protocol name with `:` at the end, `skype:`
*   for example). `linkify-it` makes shure that prefix is not preceeded with
*   alphanumeric char and symbols. Only whitespaces and punctuation allowed.
* - __value__ - rule to check tail after link prefix
*   - _String_ - just alias to existing rule
*   - _Object_
*     - _validate_ - validator function (should return matched length on success),
*       or `RegExp`.
*     - _normalize_ - optional function to normalize text & url of matched result
*       (for example, for @twitter mentions).
*
* `options`:
*
* - __fuzzyLink__ - recognige URL-s without `http(s):` prefix. Default `true`.
* - __fuzzyIP__ - allow IPs in fuzzy links above. Can conflict with some texts
*   like version numbers. Default `false`.
* - __fuzzyEmail__ - recognize emails without `mailto:` prefix.
*
**/
function LinkifyIt(schemas, options) {
	if (!(this instanceof LinkifyIt)) return new LinkifyIt(schemas, options);
	if (!options) {
		if (isOptionsObj(schemas)) {
			options = schemas;
			schemas = {};
		}
	}
	this.__opts__ = assign({}, defaultOptions, options);
	this.__index__ = -1;
	this.__last_index__ = -1;
	this.__schema__ = "";
	this.__text_cache__ = "";
	this.__schemas__ = assign({}, defaultSchemas, schemas);
	this.__compiled__ = {};
	this.__tlds__ = tlds_default;
	this.__tlds_replaced__ = false;
	this.re = {};
	compile(this);
}
/** chainable
* LinkifyIt#add(schema, definition)
* - schema (String): rule name (fixed pattern prefix)
* - definition (String|RegExp|Object): schema definition
*
* Add new rule definition. See constructor description for details.
**/
LinkifyIt.prototype.add = function add(schema, definition) {
	this.__schemas__[schema] = definition;
	compile(this);
	return this;
};
/** chainable
* LinkifyIt#set(options)
* - options (Object): { fuzzyLink|fuzzyEmail|fuzzyIP: true|false }
*
* Set recognition options for links without schema.
**/
LinkifyIt.prototype.set = function set(options) {
	this.__opts__ = assign(this.__opts__, options);
	return this;
};
/**
* LinkifyIt#test(text) -> Boolean
*
* Searches linkifiable pattern and returns `true` on success or `false` on fail.
**/
LinkifyIt.prototype.test = function test(text$1) {
	this.__text_cache__ = text$1;
	this.__index__ = -1;
	if (!text$1.length) return false;
	let m, ml, me, len, shift, next, re, tld_pos, at_pos;
	if (this.re.schema_test.test(text$1)) {
		re = this.re.schema_search;
		re.lastIndex = 0;
		while ((m = re.exec(text$1)) !== null) {
			len = this.testSchemaAt(text$1, m[2], re.lastIndex);
			if (len) {
				this.__schema__ = m[2];
				this.__index__ = m.index + m[1].length;
				this.__last_index__ = m.index + m[0].length + len;
				break;
			}
		}
	}
	if (this.__opts__.fuzzyLink && this.__compiled__["http:"]) {
		tld_pos = text$1.search(this.re.host_fuzzy_test);
		if (tld_pos >= 0) {
			if (this.__index__ < 0 || tld_pos < this.__index__) {
				if ((ml = text$1.match(this.__opts__.fuzzyIP ? this.re.link_fuzzy : this.re.link_no_ip_fuzzy)) !== null) {
					shift = ml.index + ml[1].length;
					if (this.__index__ < 0 || shift < this.__index__) {
						this.__schema__ = "";
						this.__index__ = shift;
						this.__last_index__ = ml.index + ml[0].length;
					}
				}
			}
		}
	}
	if (this.__opts__.fuzzyEmail && this.__compiled__["mailto:"]) {
		at_pos = text$1.indexOf("@");
		if (at_pos >= 0) {
			if ((me = text$1.match(this.re.email_fuzzy)) !== null) {
				shift = me.index + me[1].length;
				next = me.index + me[0].length;
				if (this.__index__ < 0 || shift < this.__index__ || shift === this.__index__ && next > this.__last_index__) {
					this.__schema__ = "mailto:";
					this.__index__ = shift;
					this.__last_index__ = next;
				}
			}
		}
	}
	return this.__index__ >= 0;
};
/**
* LinkifyIt#pretest(text) -> Boolean
*
* Very quick check, that can give false positives. Returns true if link MAY BE
* can exists. Can be used for speed optimization, when you need to check that
* link NOT exists.
**/
LinkifyIt.prototype.pretest = function pretest(text$1) {
	return this.re.pretest.test(text$1);
};
/**
* LinkifyIt#testSchemaAt(text, name, position) -> Number
* - text (String): text to scan
* - name (String): rule (schema) name
* - position (Number): text offset to check from
*
* Similar to [[LinkifyIt#test]] but checks only specific protocol tail exactly
* at given position. Returns length of found pattern (0 on fail).
**/
LinkifyIt.prototype.testSchemaAt = function testSchemaAt(text$1, schema, pos) {
	if (!this.__compiled__[schema.toLowerCase()]) return 0;
	return this.__compiled__[schema.toLowerCase()].validate(text$1, pos, this);
};
/**
* LinkifyIt#match(text) -> Array|null
*
* Returns array of found link descriptions or `null` on fail. We strongly
* recommend to use [[LinkifyIt#test]] first, for best speed.
*
* ##### Result match description
*
* - __schema__ - link schema, can be empty for fuzzy links, or `//` for
*   protocol-neutral  links.
* - __index__ - offset of matched text
* - __lastIndex__ - index of next char after mathch end
* - __raw__ - matched text
* - __text__ - normalized text
* - __url__ - link, generated from matched text
**/
LinkifyIt.prototype.match = function match(text$1) {
	const result = [];
	let shift = 0;
	if (this.__index__ >= 0 && this.__text_cache__ === text$1) {
		result.push(createMatch(this, shift));
		shift = this.__last_index__;
	}
	let tail = shift ? text$1.slice(shift) : text$1;
	while (this.test(tail)) {
		result.push(createMatch(this, shift));
		tail = tail.slice(this.__last_index__);
		shift += this.__last_index__;
	}
	if (result.length) return result;
	return null;
};
/**
* LinkifyIt#matchAtStart(text) -> Match|null
*
* Returns fully-formed (not fuzzy) link if it starts at the beginning
* of the string, and null otherwise.
**/
LinkifyIt.prototype.matchAtStart = function matchAtStart(text$1) {
	this.__text_cache__ = text$1;
	this.__index__ = -1;
	if (!text$1.length) return null;
	const m = this.re.schema_at_start.exec(text$1);
	if (!m) return null;
	const len = this.testSchemaAt(text$1, m[2], m[0].length);
	if (!len) return null;
	this.__schema__ = m[2];
	this.__index__ = m.index + m[1].length;
	this.__last_index__ = m.index + m[0].length + len;
	return createMatch(this, 0);
};
/** chainable
* LinkifyIt#tlds(list [, keepOld]) -> this
* - list (Array): list of tlds
* - keepOld (Boolean): merge with current list if `true` (`false` by default)
*
* Load (or merge) new tlds list. Those are user for fuzzy links (without prefix)
* to avoid false positives. By default this algorythm used:
*
* - hostname with any 2-letter root zones are ok.
* - biz|com|edu|gov|net|org|pro|web|xxx|aero|asia|coop|info|museum|name|shop|рф
*   are ok.
* - encoded (`xn--...`) root zones are ok.
*
* If list is replaced, then exact match for 2-chars root zones will be checked.
**/
LinkifyIt.prototype.tlds = function tlds(list, keepOld) {
	list = Array.isArray(list) ? list : [list];
	if (!keepOld) {
		this.__tlds__ = list.slice();
		this.__tlds_replaced__ = true;
		compile(this);
		return this;
	}
	this.__tlds__ = this.__tlds__.concat(list).sort().filter(function(el, idx, arr) {
		return el !== arr[idx - 1];
	}).reverse();
	compile(this);
	return this;
};
/**
* LinkifyIt#normalize(match)
*
* Default normalizer (if schema does not define it's own).
**/
LinkifyIt.prototype.normalize = function normalize(match) {
	if (!match.schema) match.url = "http://" + match.url;
	if (match.schema === "mailto:" && !/^mailto:/i.test(match.url)) match.url = "mailto:" + match.url;
};
/**
* LinkifyIt#onCompile()
*
* Override to modify basic RegExp-s.
**/
LinkifyIt.prototype.onCompile = function onCompile() {};
var linkify_it_default = LinkifyIt;

//#endregion
//#region node_modules/punycode.js/punycode.es6.js
/** Highest positive signed 32-bit float value */
var maxInt = 2147483647;
/** Bootstring parameters */
var base = 36;
var tMin = 1;
var tMax = 26;
var skew = 38;
var damp = 700;
var initialBias = 72;
var initialN = 128;
var delimiter = "-";
/** Regular expressions */
var regexPunycode = /^xn--/;
var regexNonASCII = /[^\0-\x7F]/;
var regexSeparators = /[\x2E\u3002\uFF0E\uFF61]/g;
/** Error messages */
var errors = {
	"overflow": "Overflow: input needs wider integers to process",
	"not-basic": "Illegal input >= 0x80 (not a basic code point)",
	"invalid-input": "Invalid input"
};
/** Convenience shortcuts */
var baseMinusTMin = base - tMin;
var floor = Math.floor;
var stringFromCharCode = String.fromCharCode;
/**
* A generic error utility function.
* @private
* @param {String} type The error type.
* @returns {Error} Throws a `RangeError` with the applicable error message.
*/
function error(type) {
	throw new RangeError(errors[type]);
}
/**
* A generic `Array#map` utility function.
* @private
* @param {Array} array The array to iterate over.
* @param {Function} callback The function that gets called for every array
* item.
* @returns {Array} A new array of values returned by the callback function.
*/
function map(array, callback) {
	const result = [];
	let length = array.length;
	while (length--) result[length] = callback(array[length]);
	return result;
}
/**
* A simple `Array#map`-like wrapper to work with domain name strings or email
* addresses.
* @private
* @param {String} domain The domain name or email address.
* @param {Function} callback The function that gets called for every
* character.
* @returns {String} A new string of characters returned by the callback
* function.
*/
function mapDomain(domain, callback) {
	const parts = domain.split("@");
	let result = "";
	if (parts.length > 1) {
		result = parts[0] + "@";
		domain = parts[1];
	}
	domain = domain.replace(regexSeparators, ".");
	const encoded = map(domain.split("."), callback).join(".");
	return result + encoded;
}
/**
* Creates an array containing the numeric code points of each Unicode
* character in the string. While JavaScript uses UCS-2 internally,
* this function will convert a pair of surrogate halves (each of which
* UCS-2 exposes as separate characters) into a single code point,
* matching UTF-16.
* @see `punycode.ucs2.encode`
* @see <https://mathiasbynens.be/notes/javascript-encoding>
* @memberOf punycode.ucs2
* @name decode
* @param {String} string The Unicode input string (UCS-2).
* @returns {Array} The new array of code points.
*/
function ucs2decode(string) {
	const output = [];
	let counter = 0;
	const length = string.length;
	while (counter < length) {
		const value = string.charCodeAt(counter++);
		if (value >= 55296 && value <= 56319 && counter < length) {
			const extra = string.charCodeAt(counter++);
			if ((extra & 64512) == 56320) output.push(((value & 1023) << 10) + (extra & 1023) + 65536);
			else {
				output.push(value);
				counter--;
			}
		} else output.push(value);
	}
	return output;
}
/**
* Creates a string based on an array of numeric code points.
* @see `punycode.ucs2.decode`
* @memberOf punycode.ucs2
* @name encode
* @param {Array} codePoints The array of numeric code points.
* @returns {String} The new Unicode string (UCS-2).
*/
var ucs2encode = (codePoints) => String.fromCodePoint(...codePoints);
/**
* Converts a basic code point into a digit/integer.
* @see `digitToBasic()`
* @private
* @param {Number} codePoint The basic numeric code point value.
* @returns {Number} The numeric value of a basic code point (for use in
* representing integers) in the range `0` to `base - 1`, or `base` if
* the code point does not represent a value.
*/
var basicToDigit = function(codePoint) {
	if (codePoint >= 48 && codePoint < 58) return 26 + (codePoint - 48);
	if (codePoint >= 65 && codePoint < 91) return codePoint - 65;
	if (codePoint >= 97 && codePoint < 123) return codePoint - 97;
	return base;
};
/**
* Converts a digit/integer into a basic code point.
* @see `basicToDigit()`
* @private
* @param {Number} digit The numeric value of a basic code point.
* @returns {Number} The basic code point whose value (when used for
* representing integers) is `digit`, which needs to be in the range
* `0` to `base - 1`. If `flag` is non-zero, the uppercase form is
* used; else, the lowercase form is used. The behavior is undefined
* if `flag` is non-zero and `digit` has no uppercase form.
*/
var digitToBasic = function(digit, flag) {
	return digit + 22 + 75 * (digit < 26) - ((flag != 0) << 5);
};
/**
* Bias adaptation function as per section 3.4 of RFC 3492.
* https://tools.ietf.org/html/rfc3492#section-3.4
* @private
*/
var adapt = function(delta, numPoints, firstTime) {
	let k = 0;
	delta = firstTime ? floor(delta / damp) : delta >> 1;
	delta += floor(delta / numPoints);
	for (; delta > baseMinusTMin * tMax >> 1; k += base) delta = floor(delta / baseMinusTMin);
	return floor(k + (baseMinusTMin + 1) * delta / (delta + skew));
};
/**
* Converts a Punycode string of ASCII-only symbols to a string of Unicode
* symbols.
* @memberOf punycode
* @param {String} input The Punycode string of ASCII-only symbols.
* @returns {String} The resulting string of Unicode symbols.
*/
var decode = function(input) {
	const output = [];
	const inputLength = input.length;
	let i = 0;
	let n = initialN;
	let bias = initialBias;
	let basic = input.lastIndexOf(delimiter);
	if (basic < 0) basic = 0;
	for (let j = 0; j < basic; ++j) {
		if (input.charCodeAt(j) >= 128) error("not-basic");
		output.push(input.charCodeAt(j));
	}
	for (let index = basic > 0 ? basic + 1 : 0; index < inputLength;) {
		const oldi = i;
		for (let w = 1, k = base;; k += base) {
			if (index >= inputLength) error("invalid-input");
			const digit = basicToDigit(input.charCodeAt(index++));
			if (digit >= base) error("invalid-input");
			if (digit > floor((maxInt - i) / w)) error("overflow");
			i += digit * w;
			const t = k <= bias ? tMin : k >= bias + tMax ? tMax : k - bias;
			if (digit < t) break;
			const baseMinusT = base - t;
			if (w > floor(maxInt / baseMinusT)) error("overflow");
			w *= baseMinusT;
		}
		const out = output.length + 1;
		bias = adapt(i - oldi, out, oldi == 0);
		if (floor(i / out) > maxInt - n) error("overflow");
		n += floor(i / out);
		i %= out;
		output.splice(i++, 0, n);
	}
	return String.fromCodePoint(...output);
};
/**
* Converts a string of Unicode symbols (e.g. a domain name label) to a
* Punycode string of ASCII-only symbols.
* @memberOf punycode
* @param {String} input The string of Unicode symbols.
* @returns {String} The resulting Punycode string of ASCII-only symbols.
*/
var encode = function(input) {
	const output = [];
	input = ucs2decode(input);
	const inputLength = input.length;
	let n = initialN;
	let delta = 0;
	let bias = initialBias;
	for (const currentValue of input) if (currentValue < 128) output.push(stringFromCharCode(currentValue));
	const basicLength = output.length;
	let handledCPCount = basicLength;
	if (basicLength) output.push(delimiter);
	while (handledCPCount < inputLength) {
		let m = maxInt;
		for (const currentValue of input) if (currentValue >= n && currentValue < m) m = currentValue;
		const handledCPCountPlusOne = handledCPCount + 1;
		if (m - n > floor((maxInt - delta) / handledCPCountPlusOne)) error("overflow");
		delta += (m - n) * handledCPCountPlusOne;
		n = m;
		for (const currentValue of input) {
			if (currentValue < n && ++delta > maxInt) error("overflow");
			if (currentValue === n) {
				let q = delta;
				for (let k = base;; k += base) {
					const t = k <= bias ? tMin : k >= bias + tMax ? tMax : k - bias;
					if (q < t) break;
					const qMinusT = q - t;
					const baseMinusT = base - t;
					output.push(stringFromCharCode(digitToBasic(t + qMinusT % baseMinusT, 0)));
					q = floor(qMinusT / baseMinusT);
				}
				output.push(stringFromCharCode(digitToBasic(q, 0)));
				bias = adapt(delta, handledCPCountPlusOne, handledCPCount === basicLength);
				delta = 0;
				++handledCPCount;
			}
		}
		++delta;
		++n;
	}
	return output.join("");
};
/**
* Converts a Punycode string representing a domain name or an email address
* to Unicode. Only the Punycoded parts of the input will be converted, i.e.
* it doesn't matter if you call it on a string that has already been
* converted to Unicode.
* @memberOf punycode
* @param {String} input The Punycoded domain name or email address to
* convert to Unicode.
* @returns {String} The Unicode representation of the given Punycode
* string.
*/
var toUnicode = function(input) {
	return mapDomain(input, function(string) {
		return regexPunycode.test(string) ? decode(string.slice(4).toLowerCase()) : string;
	});
};
/**
* Converts a Unicode string representing a domain name or an email address to
* Punycode. Only the non-ASCII parts of the domain name will be converted,
* i.e. it doesn't matter if you call it with a domain that's already in
* ASCII.
* @memberOf punycode
* @param {String} input The domain name or email address to convert, as a
* Unicode string.
* @returns {String} The Punycode representation of the given domain name or
* email address.
*/
var toASCII = function(input) {
	return mapDomain(input, function(string) {
		return regexNonASCII.test(string) ? "xn--" + encode(string) : string;
	});
};
/** Define the public API */
var punycode = {
	"version": "2.3.1",
	"ucs2": {
		"decode": ucs2decode,
		"encode": ucs2encode
	},
	"decode": decode,
	"encode": encode,
	"toASCII": toASCII,
	"toUnicode": toUnicode
};
var punycode_es6_default = punycode;

//#endregion
//#region node_modules/dompurify/dist/purify.es.mjs
var { entries, setPrototypeOf, isFrozen, getPrototypeOf, getOwnPropertyDescriptor } = Object;
var { freeze, seal, create } = Object;
var { apply, construct } = typeof Reflect !== "undefined" && Reflect;
if (!freeze) freeze = function freeze$1(x) {
	return x;
};
if (!seal) seal = function seal$1(x) {
	return x;
};
if (!apply) apply = function apply$1(func, thisArg) {
	for (var _len = arguments.length, args = new Array(_len > 2 ? _len - 2 : 0), _key = 2; _key < _len; _key++) args[_key - 2] = arguments[_key];
	return func.apply(thisArg, args);
};
if (!construct) construct = function construct$1(Func) {
	for (var _len2 = arguments.length, args = new Array(_len2 > 1 ? _len2 - 1 : 0), _key2 = 1; _key2 < _len2; _key2++) args[_key2 - 1] = arguments[_key2];
	return new Func(...args);
};
var arrayForEach = unapply(Array.prototype.forEach);
var arrayLastIndexOf = unapply(Array.prototype.lastIndexOf);
var arrayPop = unapply(Array.prototype.pop);
var arrayPush = unapply(Array.prototype.push);
var arraySplice = unapply(Array.prototype.splice);
var stringToLowerCase = unapply(String.prototype.toLowerCase);
var stringToString = unapply(String.prototype.toString);
var stringMatch = unapply(String.prototype.match);
var stringReplace = unapply(String.prototype.replace);
var stringIndexOf = unapply(String.prototype.indexOf);
var stringTrim = unapply(String.prototype.trim);
var objectHasOwnProperty = unapply(Object.prototype.hasOwnProperty);
var regExpTest = unapply(RegExp.prototype.test);
var typeErrorCreate = unconstruct(TypeError);
/**
* Creates a new function that calls the given function with a specified thisArg and arguments.
*
* @param func - The function to be wrapped and called.
* @returns A new function that calls the given function with a specified thisArg and arguments.
*/
function unapply(func) {
	return function(thisArg) {
		if (thisArg instanceof RegExp) thisArg.lastIndex = 0;
		for (var _len3 = arguments.length, args = new Array(_len3 > 1 ? _len3 - 1 : 0), _key3 = 1; _key3 < _len3; _key3++) args[_key3 - 1] = arguments[_key3];
		return apply(func, thisArg, args);
	};
}
/**
* Creates a new function that constructs an instance of the given constructor function with the provided arguments.
*
* @param func - The constructor function to be wrapped and called.
* @returns A new function that constructs an instance of the given constructor function with the provided arguments.
*/
function unconstruct(Func) {
	return function() {
		for (var _len4 = arguments.length, args = new Array(_len4), _key4 = 0; _key4 < _len4; _key4++) args[_key4] = arguments[_key4];
		return construct(Func, args);
	};
}
/**
* Add properties to a lookup table
*
* @param set - The set to which elements will be added.
* @param array - The array containing elements to be added to the set.
* @param transformCaseFunc - An optional function to transform the case of each element before adding to the set.
* @returns The modified set with added elements.
*/
function addToSet(set, array) {
	let transformCaseFunc = arguments.length > 2 && arguments[2] !== void 0 ? arguments[2] : stringToLowerCase;
	if (setPrototypeOf) setPrototypeOf(set, null);
	let l = array.length;
	while (l--) {
		let element = array[l];
		if (typeof element === "string") {
			const lcElement = transformCaseFunc(element);
			if (lcElement !== element) {
				if (!isFrozen(array)) array[l] = lcElement;
				element = lcElement;
			}
		}
		set[element] = true;
	}
	return set;
}
/**
* Clean up an array to harden against CSPP
*
* @param array - The array to be cleaned.
* @returns The cleaned version of the array
*/
function cleanArray(array) {
	for (let index = 0; index < array.length; index++) if (!objectHasOwnProperty(array, index)) array[index] = null;
	return array;
}
/**
* Shallow clone an object
*
* @param object - The object to be cloned.
* @returns A new object that copies the original.
*/
function clone(object) {
	const newObject = create(null);
	for (const [property, value] of entries(object)) if (objectHasOwnProperty(object, property)) if (Array.isArray(value)) newObject[property] = cleanArray(value);
	else if (value && typeof value === "object" && value.constructor === Object) newObject[property] = clone(value);
	else newObject[property] = value;
	return newObject;
}
/**
* This method automatically checks if the prop is function or getter and behaves accordingly.
*
* @param object - The object to look up the getter function in its prototype chain.
* @param prop - The property name for which to find the getter function.
* @returns The getter function found in the prototype chain or a fallback function.
*/
function lookupGetter(object, prop) {
	while (object !== null) {
		const desc = getOwnPropertyDescriptor(object, prop);
		if (desc) {
			if (desc.get) return unapply(desc.get);
			if (typeof desc.value === "function") return unapply(desc.value);
		}
		object = getPrototypeOf(object);
	}
	function fallbackValue() {
		return null;
	}
	return fallbackValue;
}
var html$1 = freeze([
	"a",
	"abbr",
	"acronym",
	"address",
	"area",
	"article",
	"aside",
	"audio",
	"b",
	"bdi",
	"bdo",
	"big",
	"blink",
	"blockquote",
	"body",
	"br",
	"button",
	"canvas",
	"caption",
	"center",
	"cite",
	"code",
	"col",
	"colgroup",
	"content",
	"data",
	"datalist",
	"dd",
	"decorator",
	"del",
	"details",
	"dfn",
	"dialog",
	"dir",
	"div",
	"dl",
	"dt",
	"element",
	"em",
	"fieldset",
	"figcaption",
	"figure",
	"font",
	"footer",
	"form",
	"h1",
	"h2",
	"h3",
	"h4",
	"h5",
	"h6",
	"head",
	"header",
	"hgroup",
	"hr",
	"html",
	"i",
	"img",
	"input",
	"ins",
	"kbd",
	"label",
	"legend",
	"li",
	"main",
	"map",
	"mark",
	"marquee",
	"menu",
	"menuitem",
	"meter",
	"nav",
	"nobr",
	"ol",
	"optgroup",
	"option",
	"output",
	"p",
	"picture",
	"pre",
	"progress",
	"q",
	"rp",
	"rt",
	"ruby",
	"s",
	"samp",
	"search",
	"section",
	"select",
	"shadow",
	"slot",
	"small",
	"source",
	"spacer",
	"span",
	"strike",
	"strong",
	"style",
	"sub",
	"summary",
	"sup",
	"table",
	"tbody",
	"td",
	"template",
	"textarea",
	"tfoot",
	"th",
	"thead",
	"time",
	"tr",
	"track",
	"tt",
	"u",
	"ul",
	"var",
	"video",
	"wbr"
]);
var svg$1 = freeze([
	"svg",
	"a",
	"altglyph",
	"altglyphdef",
	"altglyphitem",
	"animatecolor",
	"animatemotion",
	"animatetransform",
	"circle",
	"clippath",
	"defs",
	"desc",
	"ellipse",
	"enterkeyhint",
	"exportparts",
	"filter",
	"font",
	"g",
	"glyph",
	"glyphref",
	"hkern",
	"image",
	"inputmode",
	"line",
	"lineargradient",
	"marker",
	"mask",
	"metadata",
	"mpath",
	"part",
	"path",
	"pattern",
	"polygon",
	"polyline",
	"radialgradient",
	"rect",
	"stop",
	"style",
	"switch",
	"symbol",
	"text",
	"textpath",
	"title",
	"tref",
	"tspan",
	"view",
	"vkern"
]);
var svgFilters = freeze([
	"feBlend",
	"feColorMatrix",
	"feComponentTransfer",
	"feComposite",
	"feConvolveMatrix",
	"feDiffuseLighting",
	"feDisplacementMap",
	"feDistantLight",
	"feDropShadow",
	"feFlood",
	"feFuncA",
	"feFuncB",
	"feFuncG",
	"feFuncR",
	"feGaussianBlur",
	"feImage",
	"feMerge",
	"feMergeNode",
	"feMorphology",
	"feOffset",
	"fePointLight",
	"feSpecularLighting",
	"feSpotLight",
	"feTile",
	"feTurbulence"
]);
var svgDisallowed = freeze([
	"animate",
	"color-profile",
	"cursor",
	"discard",
	"font-face",
	"font-face-format",
	"font-face-name",
	"font-face-src",
	"font-face-uri",
	"foreignobject",
	"hatch",
	"hatchpath",
	"mesh",
	"meshgradient",
	"meshpatch",
	"meshrow",
	"missing-glyph",
	"script",
	"set",
	"solidcolor",
	"unknown",
	"use"
]);
var mathMl$1 = freeze([
	"math",
	"menclose",
	"merror",
	"mfenced",
	"mfrac",
	"mglyph",
	"mi",
	"mlabeledtr",
	"mmultiscripts",
	"mn",
	"mo",
	"mover",
	"mpadded",
	"mphantom",
	"mroot",
	"mrow",
	"ms",
	"mspace",
	"msqrt",
	"mstyle",
	"msub",
	"msup",
	"msubsup",
	"mtable",
	"mtd",
	"mtext",
	"mtr",
	"munder",
	"munderover",
	"mprescripts"
]);
var mathMlDisallowed = freeze([
	"maction",
	"maligngroup",
	"malignmark",
	"mlongdiv",
	"mscarries",
	"mscarry",
	"msgroup",
	"mstack",
	"msline",
	"msrow",
	"semantics",
	"annotation",
	"annotation-xml",
	"mprescripts",
	"none"
]);
var text = freeze(["#text"]);
var html = freeze([
	"accept",
	"action",
	"align",
	"alt",
	"autocapitalize",
	"autocomplete",
	"autopictureinpicture",
	"autoplay",
	"background",
	"bgcolor",
	"border",
	"capture",
	"cellpadding",
	"cellspacing",
	"checked",
	"cite",
	"class",
	"clear",
	"color",
	"cols",
	"colspan",
	"controls",
	"controlslist",
	"coords",
	"crossorigin",
	"datetime",
	"decoding",
	"default",
	"dir",
	"disabled",
	"disablepictureinpicture",
	"disableremoteplayback",
	"download",
	"draggable",
	"enctype",
	"enterkeyhint",
	"exportparts",
	"face",
	"for",
	"headers",
	"height",
	"hidden",
	"high",
	"href",
	"hreflang",
	"id",
	"inert",
	"inputmode",
	"integrity",
	"ismap",
	"kind",
	"label",
	"lang",
	"list",
	"loading",
	"loop",
	"low",
	"max",
	"maxlength",
	"media",
	"method",
	"min",
	"minlength",
	"multiple",
	"muted",
	"name",
	"nonce",
	"noshade",
	"novalidate",
	"nowrap",
	"open",
	"optimum",
	"part",
	"pattern",
	"placeholder",
	"playsinline",
	"popover",
	"popovertarget",
	"popovertargetaction",
	"poster",
	"preload",
	"pubdate",
	"radiogroup",
	"readonly",
	"rel",
	"required",
	"rev",
	"reversed",
	"role",
	"rows",
	"rowspan",
	"spellcheck",
	"scope",
	"selected",
	"shape",
	"size",
	"sizes",
	"slot",
	"span",
	"srclang",
	"start",
	"src",
	"srcset",
	"step",
	"style",
	"summary",
	"tabindex",
	"title",
	"translate",
	"type",
	"usemap",
	"valign",
	"value",
	"width",
	"wrap",
	"xmlns",
	"slot"
]);
var svg = freeze([
	"accent-height",
	"accumulate",
	"additive",
	"alignment-baseline",
	"amplitude",
	"ascent",
	"attributename",
	"attributetype",
	"azimuth",
	"basefrequency",
	"baseline-shift",
	"begin",
	"bias",
	"by",
	"class",
	"clip",
	"clippathunits",
	"clip-path",
	"clip-rule",
	"color",
	"color-interpolation",
	"color-interpolation-filters",
	"color-profile",
	"color-rendering",
	"cx",
	"cy",
	"d",
	"dx",
	"dy",
	"diffuseconstant",
	"direction",
	"display",
	"divisor",
	"dur",
	"edgemode",
	"elevation",
	"end",
	"exponent",
	"fill",
	"fill-opacity",
	"fill-rule",
	"filter",
	"filterunits",
	"flood-color",
	"flood-opacity",
	"font-family",
	"font-size",
	"font-size-adjust",
	"font-stretch",
	"font-style",
	"font-variant",
	"font-weight",
	"fx",
	"fy",
	"g1",
	"g2",
	"glyph-name",
	"glyphref",
	"gradientunits",
	"gradienttransform",
	"height",
	"href",
	"id",
	"image-rendering",
	"in",
	"in2",
	"intercept",
	"k",
	"k1",
	"k2",
	"k3",
	"k4",
	"kerning",
	"keypoints",
	"keysplines",
	"keytimes",
	"lang",
	"lengthadjust",
	"letter-spacing",
	"kernelmatrix",
	"kernelunitlength",
	"lighting-color",
	"local",
	"marker-end",
	"marker-mid",
	"marker-start",
	"markerheight",
	"markerunits",
	"markerwidth",
	"maskcontentunits",
	"maskunits",
	"max",
	"mask",
	"mask-type",
	"media",
	"method",
	"mode",
	"min",
	"name",
	"numoctaves",
	"offset",
	"operator",
	"opacity",
	"order",
	"orient",
	"orientation",
	"origin",
	"overflow",
	"paint-order",
	"path",
	"pathlength",
	"patterncontentunits",
	"patterntransform",
	"patternunits",
	"points",
	"preservealpha",
	"preserveaspectratio",
	"primitiveunits",
	"r",
	"rx",
	"ry",
	"radius",
	"refx",
	"refy",
	"repeatcount",
	"repeatdur",
	"restart",
	"result",
	"rotate",
	"scale",
	"seed",
	"shape-rendering",
	"slope",
	"specularconstant",
	"specularexponent",
	"spreadmethod",
	"startoffset",
	"stddeviation",
	"stitchtiles",
	"stop-color",
	"stop-opacity",
	"stroke-dasharray",
	"stroke-dashoffset",
	"stroke-linecap",
	"stroke-linejoin",
	"stroke-miterlimit",
	"stroke-opacity",
	"stroke",
	"stroke-width",
	"style",
	"surfacescale",
	"systemlanguage",
	"tabindex",
	"tablevalues",
	"targetx",
	"targety",
	"transform",
	"transform-origin",
	"text-anchor",
	"text-decoration",
	"text-rendering",
	"textlength",
	"type",
	"u1",
	"u2",
	"unicode",
	"values",
	"viewbox",
	"visibility",
	"version",
	"vert-adv-y",
	"vert-origin-x",
	"vert-origin-y",
	"width",
	"word-spacing",
	"wrap",
	"writing-mode",
	"xchannelselector",
	"ychannelselector",
	"x",
	"x1",
	"x2",
	"xmlns",
	"y",
	"y1",
	"y2",
	"z",
	"zoomandpan"
]);
var mathMl = freeze([
	"accent",
	"accentunder",
	"align",
	"bevelled",
	"close",
	"columnsalign",
	"columnlines",
	"columnspan",
	"denomalign",
	"depth",
	"dir",
	"display",
	"displaystyle",
	"encoding",
	"fence",
	"frame",
	"height",
	"href",
	"id",
	"largeop",
	"length",
	"linethickness",
	"lspace",
	"lquote",
	"mathbackground",
	"mathcolor",
	"mathsize",
	"mathvariant",
	"maxsize",
	"minsize",
	"movablelimits",
	"notation",
	"numalign",
	"open",
	"rowalign",
	"rowlines",
	"rowspacing",
	"rowspan",
	"rspace",
	"rquote",
	"scriptlevel",
	"scriptminsize",
	"scriptsizemultiplier",
	"selection",
	"separator",
	"separators",
	"stretchy",
	"subscriptshift",
	"supscriptshift",
	"symmetric",
	"voffset",
	"width",
	"xmlns"
]);
var xml = freeze([
	"xlink:href",
	"xml:id",
	"xlink:title",
	"xml:space",
	"xmlns:xlink"
]);
var MUSTACHE_EXPR = seal(/\{\{[\w\W]*|[\w\W]*\}\}/gm);
var ERB_EXPR = seal(/<%[\w\W]*|[\w\W]*%>/gm);
var TMPLIT_EXPR = seal(/\$\{[\w\W]*/gm);
var DATA_ATTR = seal(/^data-[\-\w.\u00B7-\uFFFF]+$/);
var ARIA_ATTR = seal(/^aria-[\-\w]+$/);
var IS_ALLOWED_URI = seal(/^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp|matrix):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i);
var IS_SCRIPT_OR_DATA = seal(/^(?:\w+script|data):/i);
var ATTR_WHITESPACE = seal(/[\u0000-\u0020\u00A0\u1680\u180E\u2000-\u2029\u205F\u3000]/g);
var DOCTYPE_NAME = seal(/^html$/i);
var CUSTOM_ELEMENT = seal(/^[a-z][.\w]*(-[.\w]+)+$/i);
var EXPRESSIONS = /* @__PURE__ */ Object.freeze({
	__proto__: null,
	ARIA_ATTR,
	ATTR_WHITESPACE,
	CUSTOM_ELEMENT,
	DATA_ATTR,
	DOCTYPE_NAME,
	ERB_EXPR,
	IS_ALLOWED_URI,
	IS_SCRIPT_OR_DATA,
	MUSTACHE_EXPR,
	TMPLIT_EXPR
});
var NODE_TYPE = {
	element: 1,
	attribute: 2,
	text: 3,
	cdataSection: 4,
	entityReference: 5,
	entityNode: 6,
	progressingInstruction: 7,
	comment: 8,
	document: 9,
	documentType: 10,
	documentFragment: 11,
	notation: 12
};
var getGlobal = function getGlobal$1() {
	return typeof window === "undefined" ? null : window;
};
/**
* Creates a no-op policy for internal use only.
* Don't export this function outside this module!
* @param trustedTypes The policy factory.
* @param purifyHostElement The Script element used to load DOMPurify (to determine policy name suffix).
* @return The policy created (or null, if Trusted Types
* are not supported or creating the policy failed).
*/
var _createTrustedTypesPolicy = function _createTrustedTypesPolicy$1(trustedTypes, purifyHostElement) {
	if (typeof trustedTypes !== "object" || typeof trustedTypes.createPolicy !== "function") return null;
	let suffix = null;
	const ATTR_NAME = "data-tt-policy-suffix";
	if (purifyHostElement && purifyHostElement.hasAttribute(ATTR_NAME)) suffix = purifyHostElement.getAttribute(ATTR_NAME);
	const policyName = "dompurify" + (suffix ? "#" + suffix : "");
	try {
		return trustedTypes.createPolicy(policyName, {
			createHTML(html$2) {
				return html$2;
			},
			createScriptURL(scriptUrl) {
				return scriptUrl;
			}
		});
	} catch (_) {
		console.warn("TrustedTypes policy " + policyName + " could not be created.");
		return null;
	}
};
var _createHooksMap = function _createHooksMap$1() {
	return {
		afterSanitizeAttributes: [],
		afterSanitizeElements: [],
		afterSanitizeShadowDOM: [],
		beforeSanitizeAttributes: [],
		beforeSanitizeElements: [],
		beforeSanitizeShadowDOM: [],
		uponSanitizeAttribute: [],
		uponSanitizeElement: [],
		uponSanitizeShadowNode: []
	};
};
function createDOMPurify() {
	let window$1 = arguments.length > 0 && arguments[0] !== void 0 ? arguments[0] : getGlobal();
	const DOMPurify = (root) => createDOMPurify(root);
	DOMPurify.version = "3.3.3";
	DOMPurify.removed = [];
	if (!window$1 || !window$1.document || window$1.document.nodeType !== NODE_TYPE.document || !window$1.Element) {
		DOMPurify.isSupported = false;
		return DOMPurify;
	}
	let { document } = window$1;
	const originalDocument = document;
	const currentScript = originalDocument.currentScript;
	const { DocumentFragment, HTMLTemplateElement, Node, Element, NodeFilter, NamedNodeMap = window$1.NamedNodeMap || window$1.MozNamedAttrMap, HTMLFormElement, DOMParser, trustedTypes } = window$1;
	const ElementPrototype = Element.prototype;
	const cloneNode = lookupGetter(ElementPrototype, "cloneNode");
	const remove = lookupGetter(ElementPrototype, "remove");
	const getNextSibling = lookupGetter(ElementPrototype, "nextSibling");
	const getChildNodes = lookupGetter(ElementPrototype, "childNodes");
	const getParentNode = lookupGetter(ElementPrototype, "parentNode");
	if (typeof HTMLTemplateElement === "function") {
		const template = document.createElement("template");
		if (template.content && template.content.ownerDocument) document = template.content.ownerDocument;
	}
	let trustedTypesPolicy;
	let emptyHTML = "";
	const { implementation, createNodeIterator, createDocumentFragment, getElementsByTagName } = document;
	const { importNode } = originalDocument;
	let hooks = _createHooksMap();
	/**
	* Expose whether this browser supports running the full DOMPurify.
	*/
	DOMPurify.isSupported = typeof entries === "function" && typeof getParentNode === "function" && implementation && implementation.createHTMLDocument !== void 0;
	const { MUSTACHE_EXPR: MUSTACHE_EXPR$1, ERB_EXPR: ERB_EXPR$1, TMPLIT_EXPR: TMPLIT_EXPR$1, DATA_ATTR: DATA_ATTR$1, ARIA_ATTR: ARIA_ATTR$1, IS_SCRIPT_OR_DATA: IS_SCRIPT_OR_DATA$1, ATTR_WHITESPACE: ATTR_WHITESPACE$1, CUSTOM_ELEMENT: CUSTOM_ELEMENT$1 } = EXPRESSIONS;
	let { IS_ALLOWED_URI: IS_ALLOWED_URI$1 } = EXPRESSIONS;
	/**
	* We consider the elements and attributes below to be safe. Ideally
	* don't add any new ones but feel free to remove unwanted ones.
	*/
	let ALLOWED_TAGS = null;
	const DEFAULT_ALLOWED_TAGS = addToSet({}, [
		...html$1,
		...svg$1,
		...svgFilters,
		...mathMl$1,
		...text
	]);
	let ALLOWED_ATTR = null;
	const DEFAULT_ALLOWED_ATTR = addToSet({}, [
		...html,
		...svg,
		...mathMl,
		...xml
	]);
	let CUSTOM_ELEMENT_HANDLING = Object.seal(create(null, {
		tagNameCheck: {
			writable: true,
			configurable: false,
			enumerable: true,
			value: null
		},
		attributeNameCheck: {
			writable: true,
			configurable: false,
			enumerable: true,
			value: null
		},
		allowCustomizedBuiltInElements: {
			writable: true,
			configurable: false,
			enumerable: true,
			value: false
		}
	}));
	let FORBID_TAGS = null;
	let FORBID_ATTR = null;
	const EXTRA_ELEMENT_HANDLING = Object.seal(create(null, {
		tagCheck: {
			writable: true,
			configurable: false,
			enumerable: true,
			value: null
		},
		attributeCheck: {
			writable: true,
			configurable: false,
			enumerable: true,
			value: null
		}
	}));
	let ALLOW_ARIA_ATTR = true;
	let ALLOW_DATA_ATTR = true;
	let ALLOW_UNKNOWN_PROTOCOLS = false;
	let ALLOW_SELF_CLOSE_IN_ATTR = true;
	let SAFE_FOR_TEMPLATES = false;
	let SAFE_FOR_XML = true;
	let WHOLE_DOCUMENT = false;
	let SET_CONFIG = false;
	let FORCE_BODY = false;
	let RETURN_DOM = false;
	let RETURN_DOM_FRAGMENT = false;
	let RETURN_TRUSTED_TYPE = false;
	let SANITIZE_DOM = true;
	let SANITIZE_NAMED_PROPS = false;
	const SANITIZE_NAMED_PROPS_PREFIX = "user-content-";
	let KEEP_CONTENT = true;
	let IN_PLACE = false;
	let USE_PROFILES = {};
	let FORBID_CONTENTS = null;
	const DEFAULT_FORBID_CONTENTS = addToSet({}, [
		"annotation-xml",
		"audio",
		"colgroup",
		"desc",
		"foreignobject",
		"head",
		"iframe",
		"math",
		"mi",
		"mn",
		"mo",
		"ms",
		"mtext",
		"noembed",
		"noframes",
		"noscript",
		"plaintext",
		"script",
		"style",
		"svg",
		"template",
		"thead",
		"title",
		"video",
		"xmp"
	]);
	let DATA_URI_TAGS = null;
	const DEFAULT_DATA_URI_TAGS = addToSet({}, [
		"audio",
		"video",
		"img",
		"source",
		"image",
		"track"
	]);
	let URI_SAFE_ATTRIBUTES = null;
	const DEFAULT_URI_SAFE_ATTRIBUTES = addToSet({}, [
		"alt",
		"class",
		"for",
		"id",
		"label",
		"name",
		"pattern",
		"placeholder",
		"role",
		"summary",
		"title",
		"value",
		"style",
		"xmlns"
	]);
	const MATHML_NAMESPACE = "http://www.w3.org/1998/Math/MathML";
	const SVG_NAMESPACE = "http://www.w3.org/2000/svg";
	const HTML_NAMESPACE = "http://www.w3.org/1999/xhtml";
	let NAMESPACE = HTML_NAMESPACE;
	let IS_EMPTY_INPUT = false;
	let ALLOWED_NAMESPACES = null;
	const DEFAULT_ALLOWED_NAMESPACES = addToSet({}, [
		MATHML_NAMESPACE,
		SVG_NAMESPACE,
		HTML_NAMESPACE
	], stringToString);
	let MATHML_TEXT_INTEGRATION_POINTS = addToSet({}, [
		"mi",
		"mo",
		"mn",
		"ms",
		"mtext"
	]);
	let HTML_INTEGRATION_POINTS = addToSet({}, ["annotation-xml"]);
	const COMMON_SVG_AND_HTML_ELEMENTS = addToSet({}, [
		"title",
		"style",
		"font",
		"a",
		"script"
	]);
	let PARSER_MEDIA_TYPE = null;
	const SUPPORTED_PARSER_MEDIA_TYPES = ["application/xhtml+xml", "text/html"];
	const DEFAULT_PARSER_MEDIA_TYPE = "text/html";
	let transformCaseFunc = null;
	let CONFIG = null;
	const formElement = document.createElement("form");
	const isRegexOrFunction = function isRegexOrFunction$1(testValue) {
		return testValue instanceof RegExp || testValue instanceof Function;
	};
	/**
	* _parseConfig
	*
	* @param cfg optional config literal
	*/
	const _parseConfig = function _parseConfig$1() {
		let cfg = arguments.length > 0 && arguments[0] !== void 0 ? arguments[0] : {};
		if (CONFIG && CONFIG === cfg) return;
		if (!cfg || typeof cfg !== "object") cfg = {};
		cfg = clone(cfg);
		PARSER_MEDIA_TYPE = SUPPORTED_PARSER_MEDIA_TYPES.indexOf(cfg.PARSER_MEDIA_TYPE) === -1 ? DEFAULT_PARSER_MEDIA_TYPE : cfg.PARSER_MEDIA_TYPE;
		transformCaseFunc = PARSER_MEDIA_TYPE === "application/xhtml+xml" ? stringToString : stringToLowerCase;
		ALLOWED_TAGS = objectHasOwnProperty(cfg, "ALLOWED_TAGS") ? addToSet({}, cfg.ALLOWED_TAGS, transformCaseFunc) : DEFAULT_ALLOWED_TAGS;
		ALLOWED_ATTR = objectHasOwnProperty(cfg, "ALLOWED_ATTR") ? addToSet({}, cfg.ALLOWED_ATTR, transformCaseFunc) : DEFAULT_ALLOWED_ATTR;
		ALLOWED_NAMESPACES = objectHasOwnProperty(cfg, "ALLOWED_NAMESPACES") ? addToSet({}, cfg.ALLOWED_NAMESPACES, stringToString) : DEFAULT_ALLOWED_NAMESPACES;
		URI_SAFE_ATTRIBUTES = objectHasOwnProperty(cfg, "ADD_URI_SAFE_ATTR") ? addToSet(clone(DEFAULT_URI_SAFE_ATTRIBUTES), cfg.ADD_URI_SAFE_ATTR, transformCaseFunc) : DEFAULT_URI_SAFE_ATTRIBUTES;
		DATA_URI_TAGS = objectHasOwnProperty(cfg, "ADD_DATA_URI_TAGS") ? addToSet(clone(DEFAULT_DATA_URI_TAGS), cfg.ADD_DATA_URI_TAGS, transformCaseFunc) : DEFAULT_DATA_URI_TAGS;
		FORBID_CONTENTS = objectHasOwnProperty(cfg, "FORBID_CONTENTS") ? addToSet({}, cfg.FORBID_CONTENTS, transformCaseFunc) : DEFAULT_FORBID_CONTENTS;
		FORBID_TAGS = objectHasOwnProperty(cfg, "FORBID_TAGS") ? addToSet({}, cfg.FORBID_TAGS, transformCaseFunc) : clone({});
		FORBID_ATTR = objectHasOwnProperty(cfg, "FORBID_ATTR") ? addToSet({}, cfg.FORBID_ATTR, transformCaseFunc) : clone({});
		USE_PROFILES = objectHasOwnProperty(cfg, "USE_PROFILES") ? cfg.USE_PROFILES : false;
		ALLOW_ARIA_ATTR = cfg.ALLOW_ARIA_ATTR !== false;
		ALLOW_DATA_ATTR = cfg.ALLOW_DATA_ATTR !== false;
		ALLOW_UNKNOWN_PROTOCOLS = cfg.ALLOW_UNKNOWN_PROTOCOLS || false;
		ALLOW_SELF_CLOSE_IN_ATTR = cfg.ALLOW_SELF_CLOSE_IN_ATTR !== false;
		SAFE_FOR_TEMPLATES = cfg.SAFE_FOR_TEMPLATES || false;
		SAFE_FOR_XML = cfg.SAFE_FOR_XML !== false;
		WHOLE_DOCUMENT = cfg.WHOLE_DOCUMENT || false;
		RETURN_DOM = cfg.RETURN_DOM || false;
		RETURN_DOM_FRAGMENT = cfg.RETURN_DOM_FRAGMENT || false;
		RETURN_TRUSTED_TYPE = cfg.RETURN_TRUSTED_TYPE || false;
		FORCE_BODY = cfg.FORCE_BODY || false;
		SANITIZE_DOM = cfg.SANITIZE_DOM !== false;
		SANITIZE_NAMED_PROPS = cfg.SANITIZE_NAMED_PROPS || false;
		KEEP_CONTENT = cfg.KEEP_CONTENT !== false;
		IN_PLACE = cfg.IN_PLACE || false;
		IS_ALLOWED_URI$1 = cfg.ALLOWED_URI_REGEXP || IS_ALLOWED_URI;
		NAMESPACE = cfg.NAMESPACE || HTML_NAMESPACE;
		MATHML_TEXT_INTEGRATION_POINTS = cfg.MATHML_TEXT_INTEGRATION_POINTS || MATHML_TEXT_INTEGRATION_POINTS;
		HTML_INTEGRATION_POINTS = cfg.HTML_INTEGRATION_POINTS || HTML_INTEGRATION_POINTS;
		CUSTOM_ELEMENT_HANDLING = cfg.CUSTOM_ELEMENT_HANDLING || {};
		if (cfg.CUSTOM_ELEMENT_HANDLING && isRegexOrFunction(cfg.CUSTOM_ELEMENT_HANDLING.tagNameCheck)) CUSTOM_ELEMENT_HANDLING.tagNameCheck = cfg.CUSTOM_ELEMENT_HANDLING.tagNameCheck;
		if (cfg.CUSTOM_ELEMENT_HANDLING && isRegexOrFunction(cfg.CUSTOM_ELEMENT_HANDLING.attributeNameCheck)) CUSTOM_ELEMENT_HANDLING.attributeNameCheck = cfg.CUSTOM_ELEMENT_HANDLING.attributeNameCheck;
		if (cfg.CUSTOM_ELEMENT_HANDLING && typeof cfg.CUSTOM_ELEMENT_HANDLING.allowCustomizedBuiltInElements === "boolean") CUSTOM_ELEMENT_HANDLING.allowCustomizedBuiltInElements = cfg.CUSTOM_ELEMENT_HANDLING.allowCustomizedBuiltInElements;
		if (SAFE_FOR_TEMPLATES) ALLOW_DATA_ATTR = false;
		if (RETURN_DOM_FRAGMENT) RETURN_DOM = true;
		if (USE_PROFILES) {
			ALLOWED_TAGS = addToSet({}, text);
			ALLOWED_ATTR = create(null);
			if (USE_PROFILES.html === true) {
				addToSet(ALLOWED_TAGS, html$1);
				addToSet(ALLOWED_ATTR, html);
			}
			if (USE_PROFILES.svg === true) {
				addToSet(ALLOWED_TAGS, svg$1);
				addToSet(ALLOWED_ATTR, svg);
				addToSet(ALLOWED_ATTR, xml);
			}
			if (USE_PROFILES.svgFilters === true) {
				addToSet(ALLOWED_TAGS, svgFilters);
				addToSet(ALLOWED_ATTR, svg);
				addToSet(ALLOWED_ATTR, xml);
			}
			if (USE_PROFILES.mathMl === true) {
				addToSet(ALLOWED_TAGS, mathMl$1);
				addToSet(ALLOWED_ATTR, mathMl);
				addToSet(ALLOWED_ATTR, xml);
			}
		}
		if (!objectHasOwnProperty(cfg, "ADD_TAGS")) EXTRA_ELEMENT_HANDLING.tagCheck = null;
		if (!objectHasOwnProperty(cfg, "ADD_ATTR")) EXTRA_ELEMENT_HANDLING.attributeCheck = null;
		if (cfg.ADD_TAGS) if (typeof cfg.ADD_TAGS === "function") EXTRA_ELEMENT_HANDLING.tagCheck = cfg.ADD_TAGS;
		else {
			if (ALLOWED_TAGS === DEFAULT_ALLOWED_TAGS) ALLOWED_TAGS = clone(ALLOWED_TAGS);
			addToSet(ALLOWED_TAGS, cfg.ADD_TAGS, transformCaseFunc);
		}
		if (cfg.ADD_ATTR) if (typeof cfg.ADD_ATTR === "function") EXTRA_ELEMENT_HANDLING.attributeCheck = cfg.ADD_ATTR;
		else {
			if (ALLOWED_ATTR === DEFAULT_ALLOWED_ATTR) ALLOWED_ATTR = clone(ALLOWED_ATTR);
			addToSet(ALLOWED_ATTR, cfg.ADD_ATTR, transformCaseFunc);
		}
		if (cfg.ADD_URI_SAFE_ATTR) addToSet(URI_SAFE_ATTRIBUTES, cfg.ADD_URI_SAFE_ATTR, transformCaseFunc);
		if (cfg.FORBID_CONTENTS) {
			if (FORBID_CONTENTS === DEFAULT_FORBID_CONTENTS) FORBID_CONTENTS = clone(FORBID_CONTENTS);
			addToSet(FORBID_CONTENTS, cfg.FORBID_CONTENTS, transformCaseFunc);
		}
		if (cfg.ADD_FORBID_CONTENTS) {
			if (FORBID_CONTENTS === DEFAULT_FORBID_CONTENTS) FORBID_CONTENTS = clone(FORBID_CONTENTS);
			addToSet(FORBID_CONTENTS, cfg.ADD_FORBID_CONTENTS, transformCaseFunc);
		}
		if (KEEP_CONTENT) ALLOWED_TAGS["#text"] = true;
		if (WHOLE_DOCUMENT) addToSet(ALLOWED_TAGS, [
			"html",
			"head",
			"body"
		]);
		if (ALLOWED_TAGS.table) {
			addToSet(ALLOWED_TAGS, ["tbody"]);
			delete FORBID_TAGS.tbody;
		}
		if (cfg.TRUSTED_TYPES_POLICY) {
			if (typeof cfg.TRUSTED_TYPES_POLICY.createHTML !== "function") throw typeErrorCreate("TRUSTED_TYPES_POLICY configuration option must provide a \"createHTML\" hook.");
			if (typeof cfg.TRUSTED_TYPES_POLICY.createScriptURL !== "function") throw typeErrorCreate("TRUSTED_TYPES_POLICY configuration option must provide a \"createScriptURL\" hook.");
			trustedTypesPolicy = cfg.TRUSTED_TYPES_POLICY;
			emptyHTML = trustedTypesPolicy.createHTML("");
		} else {
			if (trustedTypesPolicy === void 0) trustedTypesPolicy = _createTrustedTypesPolicy(trustedTypes, currentScript);
			if (trustedTypesPolicy !== null && typeof emptyHTML === "string") emptyHTML = trustedTypesPolicy.createHTML("");
		}
		if (freeze) freeze(cfg);
		CONFIG = cfg;
	};
	const ALL_SVG_TAGS = addToSet({}, [
		...svg$1,
		...svgFilters,
		...svgDisallowed
	]);
	const ALL_MATHML_TAGS = addToSet({}, [...mathMl$1, ...mathMlDisallowed]);
	/**
	* @param element a DOM element whose namespace is being checked
	* @returns Return false if the element has a
	*  namespace that a spec-compliant parser would never
	*  return. Return true otherwise.
	*/
	const _checkValidNamespace = function _checkValidNamespace$1(element) {
		let parent = getParentNode(element);
		if (!parent || !parent.tagName) parent = {
			namespaceURI: NAMESPACE,
			tagName: "template"
		};
		const tagName = stringToLowerCase(element.tagName);
		const parentTagName = stringToLowerCase(parent.tagName);
		if (!ALLOWED_NAMESPACES[element.namespaceURI]) return false;
		if (element.namespaceURI === SVG_NAMESPACE) {
			if (parent.namespaceURI === HTML_NAMESPACE) return tagName === "svg";
			if (parent.namespaceURI === MATHML_NAMESPACE) return tagName === "svg" && (parentTagName === "annotation-xml" || MATHML_TEXT_INTEGRATION_POINTS[parentTagName]);
			return Boolean(ALL_SVG_TAGS[tagName]);
		}
		if (element.namespaceURI === MATHML_NAMESPACE) {
			if (parent.namespaceURI === HTML_NAMESPACE) return tagName === "math";
			if (parent.namespaceURI === SVG_NAMESPACE) return tagName === "math" && HTML_INTEGRATION_POINTS[parentTagName];
			return Boolean(ALL_MATHML_TAGS[tagName]);
		}
		if (element.namespaceURI === HTML_NAMESPACE) {
			if (parent.namespaceURI === SVG_NAMESPACE && !HTML_INTEGRATION_POINTS[parentTagName]) return false;
			if (parent.namespaceURI === MATHML_NAMESPACE && !MATHML_TEXT_INTEGRATION_POINTS[parentTagName]) return false;
			return !ALL_MATHML_TAGS[tagName] && (COMMON_SVG_AND_HTML_ELEMENTS[tagName] || !ALL_SVG_TAGS[tagName]);
		}
		if (PARSER_MEDIA_TYPE === "application/xhtml+xml" && ALLOWED_NAMESPACES[element.namespaceURI]) return true;
		return false;
	};
	/**
	* _forceRemove
	*
	* @param node a DOM node
	*/
	const _forceRemove = function _forceRemove$1(node) {
		arrayPush(DOMPurify.removed, { element: node });
		try {
			getParentNode(node).removeChild(node);
		} catch (_) {
			remove(node);
		}
	};
	/**
	* _removeAttribute
	*
	* @param name an Attribute name
	* @param element a DOM node
	*/
	const _removeAttribute = function _removeAttribute$1(name, element) {
		try {
			arrayPush(DOMPurify.removed, {
				attribute: element.getAttributeNode(name),
				from: element
			});
		} catch (_) {
			arrayPush(DOMPurify.removed, {
				attribute: null,
				from: element
			});
		}
		element.removeAttribute(name);
		if (name === "is") if (RETURN_DOM || RETURN_DOM_FRAGMENT) try {
			_forceRemove(element);
		} catch (_) {}
		else try {
			element.setAttribute(name, "");
		} catch (_) {}
	};
	/**
	* _initDocument
	*
	* @param dirty - a string of dirty markup
	* @return a DOM, filled with the dirty markup
	*/
	const _initDocument = function _initDocument$1(dirty) {
		let doc = null;
		let leadingWhitespace = null;
		if (FORCE_BODY) dirty = "<remove></remove>" + dirty;
		else {
			const matches = stringMatch(dirty, /^[\r\n\t ]+/);
			leadingWhitespace = matches && matches[0];
		}
		if (PARSER_MEDIA_TYPE === "application/xhtml+xml" && NAMESPACE === HTML_NAMESPACE) dirty = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head></head><body>" + dirty + "</body></html>";
		const dirtyPayload = trustedTypesPolicy ? trustedTypesPolicy.createHTML(dirty) : dirty;
		if (NAMESPACE === HTML_NAMESPACE) try {
			doc = new DOMParser().parseFromString(dirtyPayload, PARSER_MEDIA_TYPE);
		} catch (_) {}
		if (!doc || !doc.documentElement) {
			doc = implementation.createDocument(NAMESPACE, "template", null);
			try {
				doc.documentElement.innerHTML = IS_EMPTY_INPUT ? emptyHTML : dirtyPayload;
			} catch (_) {}
		}
		const body = doc.body || doc.documentElement;
		if (dirty && leadingWhitespace) body.insertBefore(document.createTextNode(leadingWhitespace), body.childNodes[0] || null);
		if (NAMESPACE === HTML_NAMESPACE) return getElementsByTagName.call(doc, WHOLE_DOCUMENT ? "html" : "body")[0];
		return WHOLE_DOCUMENT ? doc.documentElement : body;
	};
	/**
	* Creates a NodeIterator object that you can use to traverse filtered lists of nodes or elements in a document.
	*
	* @param root The root element or node to start traversing on.
	* @return The created NodeIterator
	*/
	const _createNodeIterator = function _createNodeIterator$1(root) {
		return createNodeIterator.call(root.ownerDocument || root, root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_COMMENT | NodeFilter.SHOW_TEXT | NodeFilter.SHOW_PROCESSING_INSTRUCTION | NodeFilter.SHOW_CDATA_SECTION, null);
	};
	/**
	* _isClobbered
	*
	* @param element element to check for clobbering attacks
	* @return true if clobbered, false if safe
	*/
	const _isClobbered = function _isClobbered$1(element) {
		return element instanceof HTMLFormElement && (typeof element.nodeName !== "string" || typeof element.textContent !== "string" || typeof element.removeChild !== "function" || !(element.attributes instanceof NamedNodeMap) || typeof element.removeAttribute !== "function" || typeof element.setAttribute !== "function" || typeof element.namespaceURI !== "string" || typeof element.insertBefore !== "function" || typeof element.hasChildNodes !== "function");
	};
	/**
	* Checks whether the given object is a DOM node.
	*
	* @param value object to check whether it's a DOM node
	* @return true is object is a DOM node
	*/
	const _isNode = function _isNode$1(value) {
		return typeof Node === "function" && value instanceof Node;
	};
	function _executeHooks(hooks$1, currentNode, data) {
		arrayForEach(hooks$1, (hook) => {
			hook.call(DOMPurify, currentNode, data, CONFIG);
		});
	}
	/**
	* _sanitizeElements
	*
	* @protect nodeName
	* @protect textContent
	* @protect removeChild
	* @param currentNode to check for permission to exist
	* @return true if node was killed, false if left alive
	*/
	const _sanitizeElements = function _sanitizeElements$1(currentNode) {
		let content = null;
		_executeHooks(hooks.beforeSanitizeElements, currentNode, null);
		if (_isClobbered(currentNode)) {
			_forceRemove(currentNode);
			return true;
		}
		const tagName = transformCaseFunc(currentNode.nodeName);
		_executeHooks(hooks.uponSanitizeElement, currentNode, {
			tagName,
			allowedTags: ALLOWED_TAGS
		});
		if (SAFE_FOR_XML && currentNode.hasChildNodes() && !_isNode(currentNode.firstElementChild) && regExpTest(/<[/\w!]/g, currentNode.innerHTML) && regExpTest(/<[/\w!]/g, currentNode.textContent)) {
			_forceRemove(currentNode);
			return true;
		}
		if (currentNode.nodeType === NODE_TYPE.progressingInstruction) {
			_forceRemove(currentNode);
			return true;
		}
		if (SAFE_FOR_XML && currentNode.nodeType === NODE_TYPE.comment && regExpTest(/<[/\w]/g, currentNode.data)) {
			_forceRemove(currentNode);
			return true;
		}
		if (!(EXTRA_ELEMENT_HANDLING.tagCheck instanceof Function && EXTRA_ELEMENT_HANDLING.tagCheck(tagName)) && (!ALLOWED_TAGS[tagName] || FORBID_TAGS[tagName])) {
			if (!FORBID_TAGS[tagName] && _isBasicCustomElement(tagName)) {
				if (CUSTOM_ELEMENT_HANDLING.tagNameCheck instanceof RegExp && regExpTest(CUSTOM_ELEMENT_HANDLING.tagNameCheck, tagName)) return false;
				if (CUSTOM_ELEMENT_HANDLING.tagNameCheck instanceof Function && CUSTOM_ELEMENT_HANDLING.tagNameCheck(tagName)) return false;
			}
			if (KEEP_CONTENT && !FORBID_CONTENTS[tagName]) {
				const parentNode = getParentNode(currentNode) || currentNode.parentNode;
				const childNodes = getChildNodes(currentNode) || currentNode.childNodes;
				if (childNodes && parentNode) {
					const childCount = childNodes.length;
					for (let i = childCount - 1; i >= 0; --i) {
						const childClone = cloneNode(childNodes[i], true);
						childClone.__removalCount = (currentNode.__removalCount || 0) + 1;
						parentNode.insertBefore(childClone, getNextSibling(currentNode));
					}
				}
			}
			_forceRemove(currentNode);
			return true;
		}
		if (currentNode instanceof Element && !_checkValidNamespace(currentNode)) {
			_forceRemove(currentNode);
			return true;
		}
		if ((tagName === "noscript" || tagName === "noembed" || tagName === "noframes") && regExpTest(/<\/no(script|embed|frames)/i, currentNode.innerHTML)) {
			_forceRemove(currentNode);
			return true;
		}
		if (SAFE_FOR_TEMPLATES && currentNode.nodeType === NODE_TYPE.text) {
			content = currentNode.textContent;
			arrayForEach([
				MUSTACHE_EXPR$1,
				ERB_EXPR$1,
				TMPLIT_EXPR$1
			], (expr) => {
				content = stringReplace(content, expr, " ");
			});
			if (currentNode.textContent !== content) {
				arrayPush(DOMPurify.removed, { element: currentNode.cloneNode() });
				currentNode.textContent = content;
			}
		}
		_executeHooks(hooks.afterSanitizeElements, currentNode, null);
		return false;
	};
	/**
	* _isValidAttribute
	*
	* @param lcTag Lowercase tag name of containing element.
	* @param lcName Lowercase attribute name.
	* @param value Attribute value.
	* @return Returns true if `value` is valid, otherwise false.
	*/
	const _isValidAttribute = function _isValidAttribute$1(lcTag, lcName, value) {
		if (FORBID_ATTR[lcName]) return false;
		if (SANITIZE_DOM && (lcName === "id" || lcName === "name") && (value in document || value in formElement)) return false;
		if (ALLOW_DATA_ATTR && !FORBID_ATTR[lcName] && regExpTest(DATA_ATTR$1, lcName));
		else if (ALLOW_ARIA_ATTR && regExpTest(ARIA_ATTR$1, lcName));
		else if (EXTRA_ELEMENT_HANDLING.attributeCheck instanceof Function && EXTRA_ELEMENT_HANDLING.attributeCheck(lcName, lcTag));
		else if (!ALLOWED_ATTR[lcName] || FORBID_ATTR[lcName]) if (_isBasicCustomElement(lcTag) && (CUSTOM_ELEMENT_HANDLING.tagNameCheck instanceof RegExp && regExpTest(CUSTOM_ELEMENT_HANDLING.tagNameCheck, lcTag) || CUSTOM_ELEMENT_HANDLING.tagNameCheck instanceof Function && CUSTOM_ELEMENT_HANDLING.tagNameCheck(lcTag)) && (CUSTOM_ELEMENT_HANDLING.attributeNameCheck instanceof RegExp && regExpTest(CUSTOM_ELEMENT_HANDLING.attributeNameCheck, lcName) || CUSTOM_ELEMENT_HANDLING.attributeNameCheck instanceof Function && CUSTOM_ELEMENT_HANDLING.attributeNameCheck(lcName, lcTag)) || lcName === "is" && CUSTOM_ELEMENT_HANDLING.allowCustomizedBuiltInElements && (CUSTOM_ELEMENT_HANDLING.tagNameCheck instanceof RegExp && regExpTest(CUSTOM_ELEMENT_HANDLING.tagNameCheck, value) || CUSTOM_ELEMENT_HANDLING.tagNameCheck instanceof Function && CUSTOM_ELEMENT_HANDLING.tagNameCheck(value)));
		else return false;
		else if (URI_SAFE_ATTRIBUTES[lcName]);
		else if (regExpTest(IS_ALLOWED_URI$1, stringReplace(value, ATTR_WHITESPACE$1, "")));
		else if ((lcName === "src" || lcName === "xlink:href" || lcName === "href") && lcTag !== "script" && stringIndexOf(value, "data:") === 0 && DATA_URI_TAGS[lcTag]);
		else if (ALLOW_UNKNOWN_PROTOCOLS && !regExpTest(IS_SCRIPT_OR_DATA$1, stringReplace(value, ATTR_WHITESPACE$1, "")));
		else if (value) return false;
		return true;
	};
	/**
	* _isBasicCustomElement
	* checks if at least one dash is included in tagName, and it's not the first char
	* for more sophisticated checking see https://github.com/sindresorhus/validate-element-name
	*
	* @param tagName name of the tag of the node to sanitize
	* @returns Returns true if the tag name meets the basic criteria for a custom element, otherwise false.
	*/
	const _isBasicCustomElement = function _isBasicCustomElement$1(tagName) {
		return tagName !== "annotation-xml" && stringMatch(tagName, CUSTOM_ELEMENT$1);
	};
	/**
	* _sanitizeAttributes
	*
	* @protect attributes
	* @protect nodeName
	* @protect removeAttribute
	* @protect setAttribute
	*
	* @param currentNode to sanitize
	*/
	const _sanitizeAttributes = function _sanitizeAttributes$1(currentNode) {
		_executeHooks(hooks.beforeSanitizeAttributes, currentNode, null);
		const { attributes } = currentNode;
		if (!attributes || _isClobbered(currentNode)) return;
		const hookEvent = {
			attrName: "",
			attrValue: "",
			keepAttr: true,
			allowedAttributes: ALLOWED_ATTR,
			forceKeepAttr: void 0
		};
		let l = attributes.length;
		while (l--) {
			const { name, namespaceURI, value: attrValue } = attributes[l];
			const lcName = transformCaseFunc(name);
			const initValue = attrValue;
			let value = name === "value" ? initValue : stringTrim(initValue);
			hookEvent.attrName = lcName;
			hookEvent.attrValue = value;
			hookEvent.keepAttr = true;
			hookEvent.forceKeepAttr = void 0;
			_executeHooks(hooks.uponSanitizeAttribute, currentNode, hookEvent);
			value = hookEvent.attrValue;
			if (SANITIZE_NAMED_PROPS && (lcName === "id" || lcName === "name")) {
				_removeAttribute(name, currentNode);
				value = SANITIZE_NAMED_PROPS_PREFIX + value;
			}
			if (SAFE_FOR_XML && regExpTest(/((--!?|])>)|<\/(style|script|title|xmp|textarea|noscript|iframe|noembed|noframes)/i, value)) {
				_removeAttribute(name, currentNode);
				continue;
			}
			if (lcName === "attributename" && stringMatch(value, "href")) {
				_removeAttribute(name, currentNode);
				continue;
			}
			if (hookEvent.forceKeepAttr) continue;
			if (!hookEvent.keepAttr) {
				_removeAttribute(name, currentNode);
				continue;
			}
			if (!ALLOW_SELF_CLOSE_IN_ATTR && regExpTest(/\/>/i, value)) {
				_removeAttribute(name, currentNode);
				continue;
			}
			if (SAFE_FOR_TEMPLATES) arrayForEach([
				MUSTACHE_EXPR$1,
				ERB_EXPR$1,
				TMPLIT_EXPR$1
			], (expr) => {
				value = stringReplace(value, expr, " ");
			});
			const lcTag = transformCaseFunc(currentNode.nodeName);
			if (!_isValidAttribute(lcTag, lcName, value)) {
				_removeAttribute(name, currentNode);
				continue;
			}
			if (trustedTypesPolicy && typeof trustedTypes === "object" && typeof trustedTypes.getAttributeType === "function") if (namespaceURI);
			else switch (trustedTypes.getAttributeType(lcTag, lcName)) {
				case "TrustedHTML":
					value = trustedTypesPolicy.createHTML(value);
					break;
				case "TrustedScriptURL":
					value = trustedTypesPolicy.createScriptURL(value);
					break;
			}
			if (value !== initValue) try {
				if (namespaceURI) currentNode.setAttributeNS(namespaceURI, name, value);
				else currentNode.setAttribute(name, value);
				if (_isClobbered(currentNode)) _forceRemove(currentNode);
				else arrayPop(DOMPurify.removed);
			} catch (_) {
				_removeAttribute(name, currentNode);
			}
		}
		_executeHooks(hooks.afterSanitizeAttributes, currentNode, null);
	};
	/**
	* _sanitizeShadowDOM
	*
	* @param fragment to iterate over recursively
	*/
	const _sanitizeShadowDOM = function _sanitizeShadowDOM$1(fragment) {
		let shadowNode = null;
		const shadowIterator = _createNodeIterator(fragment);
		_executeHooks(hooks.beforeSanitizeShadowDOM, fragment, null);
		while (shadowNode = shadowIterator.nextNode()) {
			_executeHooks(hooks.uponSanitizeShadowNode, shadowNode, null);
			_sanitizeElements(shadowNode);
			_sanitizeAttributes(shadowNode);
			if (shadowNode.content instanceof DocumentFragment) _sanitizeShadowDOM$1(shadowNode.content);
		}
		_executeHooks(hooks.afterSanitizeShadowDOM, fragment, null);
	};
	DOMPurify.sanitize = function(dirty) {
		let cfg = arguments.length > 1 && arguments[1] !== void 0 ? arguments[1] : {};
		let body = null;
		let importedNode = null;
		let currentNode = null;
		let returnNode = null;
		IS_EMPTY_INPUT = !dirty;
		if (IS_EMPTY_INPUT) dirty = "<!-->";
		if (typeof dirty !== "string" && !_isNode(dirty)) if (typeof dirty.toString === "function") {
			dirty = dirty.toString();
			if (typeof dirty !== "string") throw typeErrorCreate("dirty is not a string, aborting");
		} else throw typeErrorCreate("toString is not a function");
		if (!DOMPurify.isSupported) return dirty;
		if (!SET_CONFIG) _parseConfig(cfg);
		DOMPurify.removed = [];
		if (typeof dirty === "string") IN_PLACE = false;
		if (IN_PLACE) {
			if (dirty.nodeName) {
				const tagName = transformCaseFunc(dirty.nodeName);
				if (!ALLOWED_TAGS[tagName] || FORBID_TAGS[tagName]) throw typeErrorCreate("root node is forbidden and cannot be sanitized in-place");
			}
		} else if (dirty instanceof Node) {
			body = _initDocument("<!---->");
			importedNode = body.ownerDocument.importNode(dirty, true);
			if (importedNode.nodeType === NODE_TYPE.element && importedNode.nodeName === "BODY") body = importedNode;
			else if (importedNode.nodeName === "HTML") body = importedNode;
			else body.appendChild(importedNode);
		} else {
			if (!RETURN_DOM && !SAFE_FOR_TEMPLATES && !WHOLE_DOCUMENT && dirty.indexOf("<") === -1) return trustedTypesPolicy && RETURN_TRUSTED_TYPE ? trustedTypesPolicy.createHTML(dirty) : dirty;
			body = _initDocument(dirty);
			if (!body) return RETURN_DOM ? null : RETURN_TRUSTED_TYPE ? emptyHTML : "";
		}
		if (body && FORCE_BODY) _forceRemove(body.firstChild);
		const nodeIterator = _createNodeIterator(IN_PLACE ? dirty : body);
		while (currentNode = nodeIterator.nextNode()) {
			_sanitizeElements(currentNode);
			_sanitizeAttributes(currentNode);
			if (currentNode.content instanceof DocumentFragment) _sanitizeShadowDOM(currentNode.content);
		}
		if (IN_PLACE) return dirty;
		if (RETURN_DOM) {
			if (RETURN_DOM_FRAGMENT) {
				returnNode = createDocumentFragment.call(body.ownerDocument);
				while (body.firstChild) returnNode.appendChild(body.firstChild);
			} else returnNode = body;
			if (ALLOWED_ATTR.shadowroot || ALLOWED_ATTR.shadowrootmode) returnNode = importNode.call(originalDocument, returnNode, true);
			return returnNode;
		}
		let serializedHTML = WHOLE_DOCUMENT ? body.outerHTML : body.innerHTML;
		if (WHOLE_DOCUMENT && ALLOWED_TAGS["!doctype"] && body.ownerDocument && body.ownerDocument.doctype && body.ownerDocument.doctype.name && regExpTest(DOCTYPE_NAME, body.ownerDocument.doctype.name)) serializedHTML = "<!DOCTYPE " + body.ownerDocument.doctype.name + ">\n" + serializedHTML;
		if (SAFE_FOR_TEMPLATES) arrayForEach([
			MUSTACHE_EXPR$1,
			ERB_EXPR$1,
			TMPLIT_EXPR$1
		], (expr) => {
			serializedHTML = stringReplace(serializedHTML, expr, " ");
		});
		return trustedTypesPolicy && RETURN_TRUSTED_TYPE ? trustedTypesPolicy.createHTML(serializedHTML) : serializedHTML;
	};
	DOMPurify.setConfig = function() {
		_parseConfig(arguments.length > 0 && arguments[0] !== void 0 ? arguments[0] : {});
		SET_CONFIG = true;
	};
	DOMPurify.clearConfig = function() {
		CONFIG = null;
		SET_CONFIG = false;
	};
	DOMPurify.isValidAttribute = function(tag, attr, value) {
		if (!CONFIG) _parseConfig({});
		return _isValidAttribute(transformCaseFunc(tag), transformCaseFunc(attr), value);
	};
	DOMPurify.addHook = function(entryPoint, hookFunction) {
		if (typeof hookFunction !== "function") return;
		arrayPush(hooks[entryPoint], hookFunction);
	};
	DOMPurify.removeHook = function(entryPoint, hookFunction) {
		if (hookFunction !== void 0) {
			const index = arrayLastIndexOf(hooks[entryPoint], hookFunction);
			return index === -1 ? void 0 : arraySplice(hooks[entryPoint], index, 1)[0];
		}
		return arrayPop(hooks[entryPoint]);
	};
	DOMPurify.removeHooks = function(entryPoint) {
		hooks[entryPoint] = [];
	};
	DOMPurify.removeAllHooks = function() {
		hooks = _createHooksMap();
	};
	return DOMPurify;
}
var purify = createDOMPurify();

//#endregion
export { regex_default$5 as a, parse_default as c, decode_default as d, Virtualizer as f, observeElementRect as h, uc_exports as i, format as l, observeElementOffset as m, punycode_es6_default as n, regex_default$3 as o, elementScroll as p, linkify_it_default as r, mdurl_exports as s, purify as t, encode_default as u };