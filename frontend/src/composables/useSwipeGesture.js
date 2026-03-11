import { ref } from 'vue'
import { useConversationStore } from '../stores/conversationStore'
import { storeToRefs } from 'pinia'

const MOBILE_VIEWPORT_QUERY = '(max-width: 767px)'
const SWIPE_EDGE_TRIGGER_PX = 28
const SWIPE_OPEN_THRESHOLD_PX = 68
const SWIPE_CLOSE_THRESHOLD_PX = 52
const SWIPE_MAX_VERTICAL_DRIFT_PX = 72

export function useSwipeGesture() {
  const conversationStore = useConversationStore()
  const { isSidebarOpen } = storeToRefs(conversationStore)

  const isMobileViewport = ref(false)
  let mobileViewportQuery = null
  let desktopSidebarStateBeforeMobile = true

  const swipeState = {
    tracking: false,
    mode: '',
    startX: 0,
    startY: 0,
  }

  function isInteractiveTouchTarget(target) {
    return Boolean(target?.closest(
      'button, a, input, textarea, select, [role="button"], [contenteditable="true"], [data-no-swipe]'
    ))
  }

  function resetSwipeTracking() {
    swipeState.tracking = false
    swipeState.mode = ''
    swipeState.startX = 0
    swipeState.startY = 0
  }

  function applyViewportMode(isMobile) {
    const wasMobile = isMobileViewport.value
    isMobileViewport.value = isMobile

    if (isMobile === wasMobile) return

    if (isMobile) {
      desktopSidebarStateBeforeMobile = isSidebarOpen.value
      conversationStore.closeSidebar()
      return
    }

    if (desktopSidebarStateBeforeMobile) {
      conversationStore.openSidebar()
    } else {
      conversationStore.closeSidebar()
    }
  }

  function handleViewportMediaChange(event) {
    applyViewportMode(Boolean(event.matches))
  }

  function handleLayoutTouchStart(event) {
    resetSwipeTracking()
    if (!isMobileViewport.value || event.touches.length !== 1) return

    const touch = event.touches[0]
    const target = event.target
    const fromLeftEdge = touch.clientX <= SWIPE_EDGE_TRIGGER_PX
    const startedInSidebar = Boolean(target?.closest('.mobile-sidebar-panel'))
    const startedOnSidebarBackdrop = Boolean(target?.closest('.mobile-sidebar-backdrop'))
    if (isInteractiveTouchTarget(target) && !startedOnSidebarBackdrop) return

    if (!isSidebarOpen.value && fromLeftEdge) {
      swipeState.mode = 'open'
    } else if (isSidebarOpen.value && (startedInSidebar || startedOnSidebarBackdrop)) {
      swipeState.mode = 'close'
    } else {
      return
    }

    swipeState.tracking = true
    swipeState.startX = touch.clientX
    swipeState.startY = touch.clientY
  }

  function handleLayoutTouchMove(event) {
    if (!swipeState.tracking || event.touches.length !== 1) return

    const touch = event.touches[0]
    const deltaX = touch.clientX - swipeState.startX
    const deltaY = touch.clientY - swipeState.startY

    if (Math.abs(deltaY) > SWIPE_MAX_VERTICAL_DRIFT_PX) {
      resetSwipeTracking()
      return
    }

    if (swipeState.mode === 'open' && deltaX >= SWIPE_OPEN_THRESHOLD_PX) {
      conversationStore.openSidebar()
      resetSwipeTracking()
      return
    }

    if (swipeState.mode === 'close' && deltaX <= -SWIPE_CLOSE_THRESHOLD_PX) {
      conversationStore.closeSidebar()
      resetSwipeTracking()
    }
  }

  function handleLayoutTouchEnd() {
    resetSwipeTracking()
  }

  function onSidebarOpenChange(isOpen) {
    if (!isMobileViewport.value) {
      desktopSidebarStateBeforeMobile = isOpen
    }
  }

  function setupViewportListener() {
    if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
      mobileViewportQuery = window.matchMedia(MOBILE_VIEWPORT_QUERY)
      applyViewportMode(Boolean(mobileViewportQuery.matches))
      if (typeof mobileViewportQuery.addEventListener === 'function') {
        mobileViewportQuery.addEventListener('change', handleViewportMediaChange)
      } else if (typeof mobileViewportQuery.addListener === 'function') {
        mobileViewportQuery.addListener(handleViewportMediaChange)
      }
    }
  }

  function teardownViewportListener() {
    if (mobileViewportQuery) {
      if (typeof mobileViewportQuery.removeEventListener === 'function') {
        mobileViewportQuery.removeEventListener('change', handleViewportMediaChange)
      } else if (typeof mobileViewportQuery.removeListener === 'function') {
        mobileViewportQuery.removeListener(handleViewportMediaChange)
      }
    }
    resetSwipeTracking()
  }

  return {
    isMobileViewport,
    handleLayoutTouchStart,
    handleLayoutTouchMove,
    handleLayoutTouchEnd,
    setupViewportListener,
    teardownViewportListener,
    onSidebarOpenChange,
  }
}
