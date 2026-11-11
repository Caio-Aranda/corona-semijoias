import { ipcMain, type IpcMainInvokeEvent } from 'electron';
import { CANAIS } from '../shared/ponte';
import { validarChamada, validarEntrada, validarNovaSenha } from '../shared/validacao';
import type { ServicoDeAtualizacao } from '@corona/conexao-segura';
import type { ServicoDeSessao } from './servico-de-sessao';

/**
 * Liga os canais da ponte ao serviço. Cada canal confere quem chamou (só a janela do próprio
 * aplicativo) e o formato do que chegou; o que não bate com o formato é recusado, sem detalhes.
 */
export function registrarPonte(
  servico: ServicoDeSessao,
  remetenteConfiavel: (e: IpcMainInvokeEvent) => boolean,
): void {
  const protegido =
    <A extends unknown[], R>(fn: (...args: A) => Promise<R>) =>
    (e: IpcMainInvokeEvent, ...args: A): Promise<R> => {
      if (!remetenteConfiavel(e)) return Promise.reject(new Error('Chamada recusada.'));
      return fn(...args);
    };

  ipcMain.handle(
    CANAIS.entrar,
    protegido(async (dados: unknown) => {
      const v = validarEntrada(dados);
      if (!v) return { tipo: 'erro-inesperado' as const };
      return servico.entrar(v.usuario, v.senha);
    }),
  );
  ipcMain.handle(
    CANAIS.trocarSenha,
    protegido(async (dados: unknown) => {
      const v = validarNovaSenha(dados);
      if (!v) return { tipo: 'erro-inesperado' as const };
      return servico.trocarSenha(v.senhaNova);
    }),
  );
  ipcMain.handle(
    CANAIS.sair,
    protegido(async () => {
      await servico.sair();
    }),
  );
  ipcMain.handle(
    CANAIS.estouLogado,
    protegido(() => servico.estouLogado()),
  );
  ipcMain.handle(
    CANAIS.chamarApi,
    protegido(async (pedido: unknown) => {
      const v = validarChamada(pedido);
      if (!v) return { ok: false as const, motivo: 'erro' as const };
      return servico.chamarApi(v.operacao, v.parametros);
    }),
  );
}

/** Canal que informa a tela sobre a atualização automática (só leitura; a tela não manda nada para o atualizador). */
export function registrarAtualizacao(
  servico: ServicoDeAtualizacao,
  remetenteConfiavel: (e: IpcMainInvokeEvent) => boolean,
): void {
  ipcMain.handle(CANAIS.atualizacaoEstado, (e: IpcMainInvokeEvent) => {
    if (!remetenteConfiavel(e)) return Promise.reject(new Error('Chamada recusada.'));
    return Promise.resolve(servico.estado());
  });
}
