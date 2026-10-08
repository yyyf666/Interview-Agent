<script setup>
import { ref, computed } from 'vue';
import { UserRound, FileText, Briefcase, RotateCw, UploadCloud, Check, Trash2, RefreshCw } from 'lucide-vue-next';
import StartPanel from '../components/StartPanel.vue';
import ParsedPreview from '../components/ParsedPreview.vue';
import OnboardingCard from '../components/OnboardingCard.vue';
import { useSessionStore } from '../stores/session';
import { useUiStore } from '../stores/ui';

const session = useSessionStore();
const ui = useUiStore();

const newNickname = ref('');
const newRole = ref('');
const jdText = ref('');
const resumeFileEl = ref(null);
const jdFileEl = ref(null);

// 选中项的解析预览（R-05）：数据取自列表接口已返回的 parsed
const selectedResume = computed(() => session.resumes.find((r) => String(r.id) === String(session.resumeId)) || null);
const selectedJd = computed(() => session.jds.find((j) => String(j.id) === String(session.jdId)) || null);

function reparseResume() {
  if (!selectedResume.value) return;
  ui.guard(() => session.reparseResume(selectedResume.value.id));
}

function reparseJd() {
  if (!selectedJd.value) return;
  ui.guard(() => session.reparseJd(selectedJd.value.id));
}

// 步骤流程条：完成态由现有选择状态派生（纯展示，不改 store 逻辑）
const steps = computed(() => [
  { name: '候选人画像', done: !!session.profileId },
  { name: '简历', done: !!session.resumeId },
  { name: '目标 JD', done: !!session.jdId },
  { name: '开始面试', done: false },
]);
const activeStep = computed(() => {
  const i = steps.value.findIndex((s) => !s.done);
  return i < 0 ? steps.value.length - 1 : i;
});

function refreshProfiles() {
  ui.guard(session.loadProfiles);
}

function createProfile() {
  ui.guard(async () => {
    await session.createProfile(newNickname.value.trim(), newRole.value.trim());
    newNickname.value = '';
    newRole.value = '';
  });
}

function uploadResume() {
  const f = resumeFileEl.value && resumeFileEl.value.files[0];
  ui.guard(async () => {
    await session.uploadResume(f);
    if (resumeFileEl.value) resumeFileEl.value.value = '';
  });
}

function parseJdText() {
  ui.guard(async () => {
    await session.parseJdText(jdText.value.trim());
    jdText.value = '';
  });
}

function uploadJd() {
  const f = jdFileEl.value && jdFileEl.value.files[0];
  ui.guard(async () => {
    await session.uploadJd(f);
    if (jdFileEl.value) jdFileEl.value.value = '';
  });
}

// 删除动作：统一确认弹窗（R-19）后走 store（后端级联：历史会话解绑 + 删磁盘文件）
async function delProfile(p) {
  if (!p) return;
  const ok = await ui.ask({
    title: '删除候选人',
    message: `确定删除「${p.nickname}」？其名下简历将一并删除；历史面试会话保留但解除关联。`,
    confirmText: '删除',
    danger: true,
  });
  if (!ok) return;
  ui.guard(() => session.deleteProfile(p.id));
}

async function delResume(r) {
  const ok = await ui.ask({
    title: '删除简历',
    message: `确定删除简历「${r.fileName}」？历史面试会话将解除对该简历的关联。`,
    confirmText: '删除',
    danger: true,
  });
  if (!ok) return;
  ui.guard(() => session.deleteResume(r.id));
}

async function delJd(j) {
  const ok = await ui.ask({
    title: '删除 JD',
    message: `确定删除 JD「${j.title || '未命名岗位'}」？历史面试会话将解除对该 JD 的关联。`,
    confirmText: '删除',
    danger: true,
  });
  if (!ok) return;
  ui.guard(() => session.deleteJd(j.id));
}
</script>

<template>
  <section class="view active">
    <div class="stepper">
      <template v-for="(s, i) in steps" :key="s.name">
        <div class="step-node" :class="{ active: i === activeStep, done: s.done }">
          <span class="step-badge">
            <Check v-if="s.done" :size="14" />{{ s.done ? '' : i + 1 }}
          </span>
          <span class="step-name">{{ s.name }}</span>
        </div>
        <span v-if="i < steps.length - 1" class="step-sep"></span>
      </template>
    </div>

    <!-- 首次使用引导（R-19）：可永久关闭 -->
    <OnboardingCard />

    <div class="grid2">
      <div class="card">
        <h3><span class="ic"><UserRound :size="16" /></span>候选人画像</h3>
        <select v-model="session.profileId">
          <option v-for="p in session.profiles" :key="p.id" :value="Number(p.id)">
            {{ p.nickname }}（{{ p.targetRole || '未设岗位' }}）
          </option>
        </select>
        <button class="ghost btn-ic" @click="refreshProfiles"><span class="ic"><RotateCw :size="14" /></span>刷新</button>
        <div class="row">
          <input v-model="newNickname" placeholder="新候选人昵称" />
          <input v-model="newRole" placeholder="目标岗位" />
          <button @click="createProfile">创建</button>
        </div>
        <button class="ghost btn-ic del-cur" :disabled="!session.profiles.length" @click="delProfile(session.profiles.find(p => Number(p.id) === Number(session.profileId)) || session.profiles[0])">
          <Trash2 :size="14" /> 删除当前候选人
        </button>
      </div>

      <div class="card">
        <h3><span class="ic"><FileText :size="16" /></span>简历（PDF / Word / TXT / MD）</h3>
        <div class="dropzone">
          <span class="ic"><UploadCloud :size="20" /></span>
          <span>点击选择简历文件</span>
          <input ref="resumeFileEl" type="file" accept=".pdf,.docx,.txt,.md,.markdown" />
        </div>
        <button class="primary" @click="uploadResume">上传并解析</button>
        <div class="mini-list">
          <div
            v-for="r in session.resumes"
            :key="r.id"
            class="item"
            :class="{ selected: r.id === session.resumeId }"
            @click="session.resumeId = r.id"
          >
            <button class="del" title="删除简历" @click.stop="delResume(r)"><Trash2 :size="14" /></button>
            {{ r.fileName }}<small>{{ r.parsed ? '已解析' : '未解析' }}</small>
          </div>
          <div v-if="!session.resumes.length" class="kv">暂无数据</div>
        </div>
        <!-- 选中简历的解析预览 + 重新解析（R-05） -->
        <template v-if="selectedResume">
          <div class="pp-bar">
            <span class="pp-bar-title">解析预览</span>
            <button class="ghost btn-ic" :disabled="!selectedResume.rawText" @click="reparseResume">
              <span class="ic"><RefreshCw :size="13" /></span>重新解析
            </button>
          </div>
          <ParsedPreview kind="resume" :parsed="selectedResume.parsed" />
        </template>
      </div>

      <div class="card">
        <h3><span class="ic"><Briefcase :size="16" /></span>目标岗位 JD</h3>
        <textarea v-model="jdText" rows="5" placeholder="粘贴 JD 文本，或上传 JD 文件"></textarea>
        <div class="row">
          <button @click="parseJdText">解析文本</button>
          <div class="dropzone" style="flex: 2">
            <span class="ic"><UploadCloud :size="18" /></span>
            <span>JD 文件（PDF / Word / TXT / MD）</span>
            <input ref="jdFileEl" type="file" accept=".pdf,.docx,.txt,.md" />
          </div>
          <button @click="uploadJd">上传解析</button>
        </div>
        <div class="mini-list">
          <div
            v-for="j in session.jds"
            :key="j.id"
            class="item"
            :class="{ selected: j.id === session.jdId }"
            @click="session.jdId = j.id"
          >
            <button class="del" title="删除 JD" @click.stop="delJd(j)"><Trash2 :size="14" /></button>
            {{ j.title || '未命名岗位' }}<small>{{ j.company || '' }}</small>
          </div>
          <div v-if="!session.jds.length" class="kv">暂无数据</div>
        </div>
        <!-- 选中 JD 的解析预览 + 重新解析（R-05） -->
        <template v-if="selectedJd">
          <div class="pp-bar">
            <span class="pp-bar-title">解析预览</span>
            <button class="ghost btn-ic" :disabled="!selectedJd.rawText" @click="reparseJd">
              <span class="ic"><RefreshCw :size="13" /></span>重新解析
            </button>
          </div>
          <ParsedPreview kind="jd" :parsed="selectedJd.parsed" :raw-text="selectedJd.rawText" />
        </template>
      </div>

      <StartPanel />
    </div>
  </section>
</template>
