import { Campo, JanelaDeConfirmacao } from '@corona/ui';
import { useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, type ErroDaTela } from '../../nucleo/chamadas';
import {
  MENSAGENS,
  conferirAjuste,
  conferirMotivo,
  conferirQuantidade,
  type FormaDoAjuste,
} from '../../nucleo/estoque';
import { TEXTOS_DE_ESTOQUE } from '../../textos-estoque';

export type ModoDoLancamento = 'ENTRADA' | 'SAIDA' | 'AVARIA' | 'AJUSTE';

const TEXTO_DO_MODO = {
  ENTRADA: TEXTOS_DE_ESTOQUE.lancar.entrada,
  SAIDA: TEXTOS_DE_ESTOQUE.lancar.saida,
  AVARIA: TEXTOS_DE_ESTOQUE.lancar.perda,
} as const;

export interface LancarNoEstoqueProps {
  ponte: PonteDoCorona;
  modo: ModoDoLancamento;
  pecaId: string;
  pecaNome: string;
  /** Quantas peças há agora (para o ajuste conferir antes de enviar). */
  saldo: number;
  aoFeito: () => void;
  aoCancelar: () => void;
}

/**
 * Entrada, saída, perda ou ajuste de uma peça. O identificador do lançamento nasce quando a janela
 * abre e vale em toda tentativa: reenviar depois de uma falha nunca lança duas vezes. O botão fica
 * desligado enquanto o pedido vai, então clicar duas vezes também não duplica.
 */
export function LancarNoEstoque({
  ponte,
  modo,
  pecaId,
  pecaNome,
  saldo,
  aoFeito,
  aoCancelar,
}: LancarNoEstoqueProps) {
  const [forma, setForma] = useState<FormaDoAjuste>('CONTEI');
  const [valor, setValor] = useState('');
  const [motivo, setMotivo] = useState('');
  const [tentou, setTentou] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const idDoLancamento = useRef(crypto.randomUUID());
  const chaveDoPedido = useRef('');
  const trava = useRef(false);

  const ajuste = modo === 'AJUSTE';
  const quantidade = conferirQuantidade(valor);
  const ajustado = conferirAjuste(forma, valor, saldo);
  const valorConferido = ajuste ? ajustado : quantidade;
  const motivoConferido = ajuste
    ? conferirMotivo(motivo, MENSAGENS.motivoDoAjuste)
    : { ok: true as const, valor: motivo.trim() };

  async function enviar() {
    if (trava.current) return;
    if (!valorConferido.ok || !motivoConferido.ok) {
      setTentou(true);
      return;
    }
    trava.current = true;
    setOcupado(true);
    setErro(null);
    // Mesmo conteúdo = mesmo identificador (reenvio seguro). Conteúdo mudado = lançamento novo.
    const chave = JSON.stringify([modo, forma, valor.trim(), motivo.trim()]);
    if (chave !== chaveDoPedido.current) {
      if (chaveDoPedido.current !== '') idDoLancamento.current = crypto.randomUUID();
      chaveDoPedido.current = chave;
    }
    const base = { id: idDoLancamento.current, produtoId: pecaId };
    const r = ajuste
      ? await chamar(ponte, 'estoque.ajustar', {
          ...base,
          ...(ajustado.ok ? ajustado.valor : {}),
          motivo: motivoConferido.valor,
        })
      : await chamar(ponte, 'estoque.lancar', {
          ...base,
          tipo: modo,
          quantidade: quantidade.ok ? quantidade.valor : 0,
          ...(motivoConferido.valor ? { motivo: motivoConferido.valor } : {}),
        });
    trava.current = false;
    setOcupado(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    aoFeito();
  }

  const textos = ajuste ? TEXTOS_DE_ESTOQUE.ajuste : TEXTO_DO_MODO[modo];
  const aoApertarEnter = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      void enviar();
    }
  };

  return (
    <JanelaDeConfirmacao
      titulo={`${textos.titulo}: ${pecaNome}`}
      rotuloConfirmar={textos.confirmar}
      rotuloCancelar={TEXTOS_DE_ESTOQUE.ajuste.cancelar}
      perigosa={modo === 'SAIDA' || modo === 'AVARIA'}
      ocupado={ocupado}
      aoCancelar={aoCancelar}
      aoConfirmar={() => void enviar()}
    >
      <>
        {textos.texto}
        <p className="gestao-texto">{TEXTOS_DE_ESTOQUE.lancar.noEstoqueAgora(saldo)}</p>
        {ajuste ? (
          <fieldset className="gestao-grupo">
            <legend>{TEXTOS_DE_ESTOQUE.ajuste.forma}</legend>
            {(['CONTEI', 'DIFERENCA'] as const).map((f) => (
              <label key={f} className="gestao-opcao">
                <input
                  type="radio"
                  name="forma-do-ajuste"
                  checked={forma === f}
                  onChange={() => {
                    setForma(f);
                    setValor('');
                    setTentou(false);
                  }}
                />{' '}
                {f === 'CONTEI'
                  ? TEXTOS_DE_ESTOQUE.ajuste.contei
                  : TEXTOS_DE_ESTOQUE.ajuste.diferenca}
              </label>
            ))}
          </fieldset>
        ) : null}
        <Campo
          rotulo={
            ajuste
              ? forma === 'CONTEI'
                ? TEXTOS_DE_ESTOQUE.ajuste.campoContei
                : TEXTOS_DE_ESTOQUE.ajuste.campoDiferenca
              : TEXTOS_DE_ESTOQUE.lancar.quantidade
          }
          value={valor}
          inputMode={ajuste && forma === 'DIFERENCA' ? 'text' : 'numeric'}
          autoComplete="off"
          autoFocus
          onChange={(e) => setValor(e.target.value)}
          onKeyDown={aoApertarEnter}
          {...(tentou && !valorConferido.ok ? { erro: valorConferido.erro } : {})}
        />
        <Campo
          rotulo={ajuste ? TEXTOS_DE_ESTOQUE.ajuste.motivo : TEXTOS_DE_ESTOQUE.lancar.motivo}
          value={motivo}
          maxLength={500}
          autoComplete="off"
          onChange={(e) => setMotivo(e.target.value)}
          onKeyDown={aoApertarEnter}
          {...(tentou && !motivoConferido.ok ? { erro: motivoConferido.erro } : {})}
        />
        {erro ? <AvisoDeErro erro={erro} /> : null}
      </>
    </JanelaDeConfirmacao>
  );
}
