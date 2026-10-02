import { reactive, computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { errorText } from '../utils/errors';

// 全局 UI 态：toast / 动作锁 / 服务连接点 —— 对应旧版 toast()/guard()/conn-dot
export const useUiStore = defineStore('ui', () => {
  // 按动作分锁（R-01）：提交回答与结束面试互不阻塞
  const locks = reactive({});
  const busy = computed(() => Object.values(locks).some(Boolean));
  const toastText = ref('');
  const toastShow = ref(false);
  const toastError = ref(false);
  const connOnline = ref(false);
  let toastTimer = null;

  /* ---------------- 统一确认弹窗（R-19） ---------------- */
  // confirmState 驱动 ConfirmDialog.vue 渲染；ask() 返回 Promise<boolean>
  const confirmState = ref(null);
  let confirmResolve = null;

  function ask(opts = {}) {
    if (confirmResolve) {
      // 后到先得：旧弹窗按取消结算，避免 Promise 永挂
      const prev = confirmResolve;
      confirmResolve = null;
      prev(false);
    }
    confirmState.value = {
      title: '请确认',
      message: '',
      confirmText: '确定',
      cancelText: '取消',
      danger: false,
      requireText: '',
      ...opts,
    };
    return new Promise((resolve) => { confirmResolve = resolve; });
  }

  function answerConfirm(ok) {
    if (!confirmState.value) return;
    confirmState.value = null;
    const r = confirmResolve;
    confirmResolve = null;
    if (r) r(!!ok);
  }

  function toast(msg, isError = false) {
    toastText.value = msg;
    toastError.value = isError;
    toastShow.value = true;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (toastShow.value = false), 3200);
  }

  /** 按动作防重入执行器：同一 key 未完成时拒绝并发，不同 key 可并行 */
  async function guard(fn, key = 'global') {
    if (locks[key]) return toast('上一操作尚未完成');
    locks[key] = true;
    try {
      return await fn();
    } catch (e) {
      // R-19：错误分类统一文案（离线/超时/上游/权限…各给建议）
      toast(errorText(e), true);
    } finally {
      locks[key] = false;
    }
  }

  return { busy, toastText, toastShow, toastError, connOnline, toast, guard, confirmState, ask, answerConfirm };
});
