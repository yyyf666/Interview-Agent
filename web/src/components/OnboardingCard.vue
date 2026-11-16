<script setup>
import { ref } from 'vue';
import { Sparkles, X } from 'lucide-vue-next';

/**
 * 首次使用引导（R-19）：三步走卡片。
 * 关闭后写 localStorage['ia_onboarding_off']=1，之后不再自动出现。
 */
const KEY = 'ia_onboarding_off';
const hidden = ref(localStorage.getItem(KEY) === '1');

function dismiss() {
  localStorage.setItem(KEY, '1');
  hidden.value = true;
}
</script>

<template>
  <div v-if="!hidden" class="card onboard">
    <div class="ob-head">
      <span class="ic"><Sparkles :size="16" /></span>
      <strong>三步开始第一场面试</strong>
      <span class="spacer"></span>
      <button class="icon-btn" title="不再显示" aria-label="不再显示引导" @click="dismiss">
        <X :size="14" />
      </button>
    </div>
    <ol class="ob-steps">
      <li>
        <span class="ob-n">1</span>
        <div><b>创建候选人</b><small>填写花名与目标岗位，后续可在候选人画像页继续编辑</small></div>
      </li>
      <li>
        <span class="ob-n">2</span>
        <div><b>上传简历与 JD</b><small>支持 PDF / Word / TXT / MD，解析结果可在下方预览</small></div>
      </li>
      <li>
        <span class="ob-n">3</span>
        <div><b>配置面试并开始</b><small>可选题量、难度、代码题与追问强度，默认标准面试</small></div>
      </li>
    </ol>
    <div class="ob-foot">
      <span class="mini">关闭后不再自动显示</span>
      <button class="ghost btn-ic" @click="dismiss">知道了</button>
    </div>
  </div>
</template>
