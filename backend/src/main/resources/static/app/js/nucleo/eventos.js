// Descrição legível dos eventos da linha do tempo a partir dos dados gravados pelo domínio
// (códigos de etapa/motivo e ids de setor/especialidade), traduzidos pelo catálogo da unidade.
// Função pura: o que não for reconhecido simplesmente não é descrito (nunca inventa).

import * as rotulos from './rotulos.js';
import { formatarDataHora } from './tempo.js';

function porCampo(lista, campo, valor) {
  return (lista || []).find((x) => x[campo] === valor) || null;
}

export function descreverEvento(ev, catalogo) {
  const d = ev && ev.dados && typeof ev.dados === 'object' ? ev.dados : {};
  const cat = catalogo || {};
  const fuso = cat.unidade ? cat.unidade.fusoHorario : 'UTC';
  const etapa = (codigo) => (porCampo(cat.etapas, 'codigo', codigo) || {}).nome || codigo;
  const setor = (id) => (porCampo(cat.setores, 'id', id) || {}).nome || 'setor não listado';
  const motivo = (codigo) => (porCampo(cat.motivos, 'codigo', codigo) || {}).descricao || codigo;
  switch (ev.tipo) {
    case 'EPISODIO_ABERTO':
      return [d.etapa && `Etapa inicial: ${etapa(d.etapa)}`, d.setor_id && `Setor: ${setor(d.setor_id)}`]
        .filter(Boolean).join(' · ');
    case 'ETAPA_ALTERADA':
      return d.de && d.para ? `${etapa(d.de)} → ${etapa(d.para)}` : '';
    case 'SETOR_ALTERADO':
      return d.de && d.para ? `${setor(d.de)} → ${setor(d.para)}` : '';
    case 'BLOQUEIO_DEFINIDO':
      return [d.motivo && motivo(d.motivo), d.categoria && `(${rotulos.categoria(d.categoria)})`].filter(Boolean).join(' ');
    case 'BLOQUEIO_REMOVIDO':
      return d.motivo ? `Motivo encerrado: ${motivo(d.motivo)}` : '';
    case 'PROTOCOLO_REGISTRADO':
      return d.sistema || d.numero ? `${d.sistema || ''} ${d.numero || ''}`.trim() : '';
    case 'DESTINO_DEFINIDO': {
      if (!d.especialidade_id) return 'Especialidade não informada';
      const esp = porCampo(cat.especialidades, 'id', d.especialidade_id);
      return esp ? `Especialidade: ${esp.nome}` : '';
    }
    case 'EPISODIO_ENCERRADO':
      return d.desfecho ? `Desfecho: ${rotulos.desfecho(d.desfecho)}` : '';
    case 'PENDENCIA_CRIADA':
      return [d.categoria && rotulos.categoria(d.categoria),
        d.criticidade && `criticidade operacional ${rotulos.criticidade(d.criticidade).toLowerCase()}`,
        d.prazo && `prazo ${formatarDataHora(d.prazo, fuso)}`].filter(Boolean).join(' · ');
    case 'PENDENCIA_ATUALIZADA':
      if (d.campo === 'prazo' && d.de && d.para) {
        return `Prazo: ${formatarDataHora(d.de, fuso)} → ${formatarDataHora(d.para, fuso)}`;
      }
      return d.campo === 'responsavel' ? 'Responsável alterado' : '';
    case 'PENDENCIA_ENCERRADA':
      return d.status ? rotulos.statusPendencia(d.status) : '';
    default:
      return '';
  }
}
