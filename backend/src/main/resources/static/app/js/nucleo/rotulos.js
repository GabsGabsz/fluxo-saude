// Rótulos em português para os valores da API. Valor desconhecido aparece como veio (nunca some).

const mapa = (m) => (v) => (v === null || v === undefined ? '—' : (m[v] || String(v)));

export const papel = mapa({
  ADMINISTRADOR: 'Administração', COORDENACAO_FLUXO: 'Coordenação de fluxo', ENFERMAGEM: 'Enfermagem',
  MEDICO: 'Médico(a)', TRANSPORTE: 'Transporte', DIRECAO: 'Direção', AUDITORIA: 'Auditoria',
});
export const PAPEIS = ['ADMINISTRADOR', 'COORDENACAO_FLUXO', 'ENFERMAGEM', 'MEDICO', 'TRANSPORTE', 'DIRECAO', 'AUDITORIA'];

export const categoria = mapa({
  ASSISTENCIAL: 'Assistencial', REGULACAO: 'Regulação', LOGISTICA: 'Logística',
  LEITO_CAPACIDADE: 'Leito/capacidade', ADMINISTRATIVO: 'Administrativo', OUTROS: 'Outros',
  NAO_DEFINIDA: 'Causa em investigação',
});
export const CATEGORIAS = ['ASSISTENCIAL', 'REGULACAO', 'LOGISTICA', 'LEITO_CAPACIDADE', 'ADMINISTRATIVO', 'OUTROS'];

/** Criticidade OPERACIONAL (urgência de ação) — nunca risco clínico (RN-013). */
export const criticidade = mapa({ BAIXA: 'Baixa', MEDIA: 'Média', ALTA: 'Alta', CRITICA: 'Crítica' });
export const CRITICIDADES = ['BAIXA', 'MEDIA', 'ALTA', 'CRITICA'];

export const natureza = mapa({
  ATENDIMENTO: 'Atendimento', ESPERA: 'Espera', ACEITO: 'Aceito', TRANSPORTE: 'Transporte', DESFECHO: 'Desfecho',
});

export const statusPendencia = mapa({ ABERTA: 'Aberta', RESOLVIDA: 'Resolvida', CANCELADA: 'Cancelada',
  ENCERRADA_POR_DESFECHO: 'Encerrada pelo desfecho' });

export const desfecho = mapa({
  ALTA: 'Alta', TRANSFERENCIA: 'Transferência', INTERNACAO: 'Internação', OBITO: 'Óbito', EVASAO: 'Evasão',
  ENCERRAMENTO_ADMINISTRATIVO: 'Encerramento administrativo',
});

export const tipoRegra = mapa({
  TEMPO_NA_ETAPA: 'Tempo na etapa', TEMPO_TOTAL: 'Tempo total', TEMPO_BLOQUEADO: 'Tempo bloqueado',
  SEM_ATUALIZACAO: 'Sem atualização', PENDENCIA_VENCIDA: 'Pendência vencida',
});
export const TIPOS_REGRA = ['TEMPO_NA_ETAPA', 'TEMPO_TOTAL', 'TEMPO_BLOQUEADO', 'SEM_ATUALIZACAO', 'PENDENCIA_VENCIDA'];

export const ordem = mapa({
  TEMPO_NA_ETAPA: 'Tempo na etapa', TEMPO_TOTAL: 'Tempo total', TEMPO_BLOQUEADO: 'Tempo bloqueado',
  CRITICIDADE: 'Criticidade operacional', SETOR: 'Setor', ETAPA: 'Etapa', MOTIVO: 'Motivo', PRAZO: 'Prazo de pendência',
});
export const ORDENS = ['TEMPO_NA_ETAPA', 'TEMPO_TOTAL', 'TEMPO_BLOQUEADO', 'CRITICIDADE', 'PRAZO', 'SETOR', 'ETAPA', 'MOTIVO'];

export const evento = mapa({
  EPISODIO_ABERTO: 'Episódio aberto', DUPLICIDADE_JUSTIFICADA: 'Duplicidade justificada', ETAPA_ALTERADA: 'Etapa alterada',
  BLOQUEIO_DEFINIDO: 'Bloqueio definido', BLOQUEIO_REMOVIDO: 'Bloqueio removido', PROTOCOLO_REGISTRADO: 'Protocolo registrado',
  DESTINO_DEFINIDO: 'Destino definido', SETOR_ALTERADO: 'Setor alterado', PENDENCIA_CRIADA: 'Pendência criada',
  PENDENCIA_ATUALIZADA: 'Pendência atualizada', PENDENCIA_ENCERRADA: 'Pendência encerrada',
  OBSERVACAO_REGISTRADA: 'Observação registrada', EPISODIO_ENCERRADO: 'Episódio encerrado', CORRECAO: 'Correção',
  DIVERGENCIA_REGISTRADA: 'Divergência registrada', DIVERGENCIA_RESOLVIDA: 'Divergência resolvida',
});

/** Responsável legível de uma pendência da API (usuário, setor ou perfil). */
export function responsavel(p) {
  if (p.responsavelUsuarioNome) return p.responsavelUsuarioNome;
  if (p.responsavelSetorNome) return `Setor: ${p.responsavelSetorNome}`;
  if (p.responsavelPapel) return `Perfil: ${papel(p.responsavelPapel)}`;
  if (p.responsavel) return p.responsavel;
  return '—';
}
