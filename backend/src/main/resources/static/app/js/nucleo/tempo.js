// Tempo na interface: a REFERÊNCIA é o relógio do servidor (campo "agora" das respostas).
// O navegador só avança o cronômetro a partir dessa referência; datas são exibidas no fuso
// da UNIDADE (catálogo), nunca no fuso do computador.

export function criarRelogio(agoraLocal = () => Date.now()) {
  let deslocamento = 0; // servidor - local, em ms
  let sincronizado = false;
  return {
    /** Registra o "agora" do servidor recebido numa resposta. */
    sincronizar(agoraServidorIso) {
      const t = Date.parse(agoraServidorIso);
      if (!Number.isNaN(t)) {
        deslocamento = t - agoraLocal();
        sincronizado = true;
      }
    },
    agora: () => agoraLocal() + deslocamento,
    sincronizado: () => sincronizado,
  };
}

/** Duração em ms → "2 h 05 min", "45 min", "3 d 04 h". Negativo vira zero. */
export function formatarDuracao(ms) {
  if (ms === null || ms === undefined || Number.isNaN(ms)) return '—';
  const totalMin = Math.max(0, Math.floor(ms / 60000));
  const dias = Math.floor(totalMin / 1440);
  const horas = Math.floor((totalMin % 1440) / 60);
  const minutos = totalMin % 60;
  const dois = (n) => String(n).padStart(2, '0');
  if (dias > 0) return `${dias} d ${dois(horas)} h`;
  if (horas > 0) return `${horas} h ${dois(minutos)} min`;
  return `${minutos} min`;
}

/** Tempo decorrido desde um instante ISO até o "agora" do servidor. */
export function decorrido(desdeIso, agoraMs) {
  if (!desdeIso) return null;
  const t = Date.parse(desdeIso);
  return Number.isNaN(t) ? null : agoraMs - t;
}

function partes(ms, fuso) {
  const f = new Intl.DateTimeFormat('en-US', {
    timeZone: fuso, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
  const p = {};
  for (const { type, value } of f.formatToParts(new Date(ms))) p[type] = value;
  return p;
}

/** Data/hora no fuso da unidade: "06/10/2026 14:05". */
export function formatarDataHora(iso, fuso) {
  if (!iso) return '—';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return '—';
  const p = partes(t, fuso);
  return `${p.day}/${p.month}/${p.year} ${p.hour}:${p.minute}`;
}

export function formatarHora(iso, fuso) {
  if (!iso) return '—';
  const p = partes(Date.parse(iso), fuso);
  return `${p.hour}:${p.minute}`;
}

/** Deslocamento do fuso (ms) num instante: hora local do fuso - UTC. */
function deslocamentoDoFuso(ms, fuso) {
  const p = partes(ms, fuso);
  const comoUtc = Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute, +p.second);
  return comoUtc - Math.floor(ms / 1000) * 1000;
}

/**
 * Valor de <input type="datetime-local"> ("2026-10-06T14:05") interpretado no fuso da
 * UNIDADE → instante ISO UTC. (Duas iterações tratam mudanças de horário de verão.)
 */
export function localDaUnidadeParaIso(valor, fuso) {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(valor || '');
  if (!m) return null;
  const comoUtc = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]);
  let t = comoUtc - deslocamentoDoFuso(comoUtc, fuso);
  t = comoUtc - deslocamentoDoFuso(t, fuso);
  return new Date(t).toISOString();
}

/** Instante → valor para <input type="datetime-local"> no fuso da unidade. */
export function isoParaLocalDaUnidade(ms, fuso) {
  const p = partes(ms, fuso);
  return `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}`;
}
