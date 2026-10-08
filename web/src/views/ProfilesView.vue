<script setup>
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ArrowLeft, UserRound, Briefcase, CalendarClock, Layers, AlertTriangle, Upload, ShieldCheck } from 'lucide-vue-next';
import { postJson } from '../api/client';
import { useSessionStore } from '../stores/session';
import { useUiStore } from '../stores/ui';

// 候选人画像列表：入口在顶栏用户下拉菜单；数据即 session.profiles（当前账户全部候选人）
const router = useRouter();
const session = useSessionStore();
const ui = useUiStore();

onMounted(() => {
  if (!session.profiles.length) ui.guard(session.loadProfiles);
});

function open(p) {
  router.push({ name: 'profile-detail', params: { id: p.id } });
}

/* ---------------- 数据资产（R-18）：导入 JSON 备份 ---------------- */
const importEl = ref(null);
function pickImport() {
  if (importEl.value) importEl.value.click();
}
async function onImportFile(e) {
  const file = (e.target.files || [])[0];
  e.target.value = '';
  if (!file) return;
  let body;
  try {
    body = JSON.parse(await file.text());
  } catch {
    ui.toast('文件不是合法 JSON', true);
    return;
  }
  ui.guard(async () => {
    const r = await postJson('/profiles/import', body);
    ui.toast(`已导入画像 #${r.profileId}（简历 ${r.resumes} 份 · JD ${r.jds} 条）`);
    await session.loadProfiles();
    router.push({ name: 'profile-detail', params: { id: r.profileId } });
  }, 'profile-import');
}

const initial = (p) => ((p.nickname || '?').slice(0, 1)).toUpperCase();
// skillTags 可能是 AI 解析出的 {skill, level, evidence} 对象数组，展示取技能名
const tagText = (x) => (typeof x === 'string' ? x : (x && (x.skill || x.name)) || '');
const tags = (p) => [...new Set((Array.isArray(p.skillTags) ? p.skillTags : []).map(tagText))].filter(Boolean).slice(0, 6);
const tagTotal = (p) => [...new Set((Array.isArray(p.skillTags) ? p.skillTags : []).map(tagText))].filter(Boolean).length;
const weakCount = (p) => (Array.isArray(p.weakPoints) ? p.weakPoints.length : 0);
const scoreCls = (v) => (v == null ? '' : Number(v) >= 80 ? 'score-hi' : Number(v) < 60 ? 'score-lo' : '');
</script>

<template>
  <section class="view active">
    <div class="view-head">
      <h2><span class="ic"><UserRound :size="18" /></span> 候选人画像</h2>
      <div class="vh-actions">
        <button class="ghost btn-ic" @click="pickImport" title="从导出的 JSON 备份恢复候选人画像">
          <span class="ic"><Upload :size="15" /></span>导入 JSON
        </button>
        <input ref="importEl" type="file" accept="application/json,.json" class="hidden-input" @change="onImportFile" />
        <button class="ghost btn-ic" @click="router.push({ name: 'setup' })">
          <span class="ic"><ArrowLeft :size="15" /></span>返回准备
        </button>
      </div>
    </div>

    <div v-if="session.profiles.length" class="profile-grid stagger">
      <div v-for="p in session.profiles" :key="p.id" class="p-card" @click="open(p)">
        <div class="p-head">
          <span class="avatar lg">{{ initial(p) }}</span>
          <div class="p-title">
            <div class="p-name">{{ p.nickname || '未命名候选人' }}</div>
            <div class="p-role">
              <span class="ic"><Briefcase :size="13" /></span>{{ p.targetRole || '未设置目标岗位' }}
            </div>
          </div>
        </div>
        <div class="p-nums">
          <span class="p-num" title="累计面试场次">
            <span class="ic"><CalendarClock :size="14" /></span>{{ p.totalInterviews || 0 }} 场
          </span>
          <span class="p-num" title="面试均分">均分 <b :class="scoreCls(p.avgScore)">{{ p.avgScore ?? '-' }}</b></span>
          <span class="p-num" title="工作年限">{{ Number(p.yearsOfExperience || 0) }} 年经验</span>
        </div>
        <div class="p-tags" v-if="tags(p).length">
          <span v-for="(t, ti) in tags(p)" :key="ti" class="chip">{{ t }}</span>
          <span class="chip muted" v-if="tagTotal(p) > 6">+{{ tagTotal(p) - 6 }}</span>
        </div>
        <div class="p-foot">
          <span class="p-meta"><span class="ic"><Layers :size="13" /></span>{{ tagTotal(p) }} 项技能</span>
          <span class="p-meta" :class="{ warn: weakCount(p) > 0 }">
            <span class="ic"><AlertTriangle :size="13" /></span>{{ weakCount(p) }} 个薄弱点
          </span>
        </div>
      </div>
    </div>

    <div v-else class="card empty-card">
      <div class="empty-ic"><span class="ic"><UserRound :size="22" /></span></div>
      <p>还没有候选人画像。去准备页创建候选人并上传简历，AI 解析后画像会自动沉淀。</p>
      <button class="primary" @click="router.push({ name: 'setup' })">去准备页创建</button>
    </div>

    <!-- 备份说明（R-18）：个人数据可导出 JSON，整库备份靠 Docker 卷 -->
    <div class="card backup-note">
      <h3><span class="ic"><ShieldCheck :size="16" /></span>数据备份与恢复</h3>
      <p class="kv">个人数据：在画像详情页「导出 JSON」下载备份（含画像 / 简历 / JD），再用「导入 JSON」恢复到任意账户。</p>
      <p class="kv">整库备份：PostgreSQL 数据位于 Docker 卷 <code>interview-agent_pg_data</code>，上传文件位于宿主机 <code>./data/uploads</code>。</p>
      <p class="kv">需要清空个人数据时，用画像详情页的「清空我的数据」（输入 DELETE 确认，账号保留）。</p>
    </div>
  </section>
</template>
