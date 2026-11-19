import { useCallback, useEffect, useState } from 'react';
import type { PedidoDoConsignado } from '../../../shared/operacoes-do-consignado';
import type { PonteDoCorona } from '../../../shared/ponte';
import type { FotoDoClientePronta } from '../../../shared/dados-do-consignado';
import { chamar, type Resposta, useFoto } from '../../chamadas';
import { centavosDeTexto, dataParaTela, formatarReais } from '../../moedas';
import { TEXTOS } from '../../textos';

/** Texto da API ("1617.00") → "R$ 1.617,00"; o que não for dinheiro aparece como veio. */
export function reais(texto: string | null | undefined): string {
  if (texto === null || texto === undefined) return '';
  const c = centavosDeTexto(texto);
  return c === null ? texto : formatarReais(c);
}

/** "2026-11-28" ou um instante ISO → "28/11/2026". */
export function dia(texto: string | null | undefined): string {
  return texto ? dataParaTela(texto) : '';
}

/** "30.00" → "30,00%". */
export function porCento(texto: string | null | undefined): string {
  return texto ? `${texto.replace('.', ',')}%` : '';
}

/** O que a pessoa digitou no campo de dinheiro ("1.470,50", "300") → "1470.50"; vazio ou errado → `null`. */
export function dinheiroDigitado(digitado: string): string | null {
  const limpo = digitado.trim();
  if (limpo === '') return null;
  const m = /^(\d{1,3}(?:\.\d{3})+|\d+)(?:,(\d{1,2}))?$/.exec(limpo);
  if (!m) return null;
  const inteiro = (m[1] ?? '').replace(/\./g, '');
  if (inteiro.length > 12) return null;
  return `${inteiro}.${(m[2] ?? '').padEnd(2, '0') || '00'}`;
}

/** Porcentagem digitada ("30", "7,5") → "30" ou "7.5"; errado → `null`. */
export function porcentagemDigitada(digitado: string): string | null {
  const m = /^(\d{1,3})(?:[.,](\d{1,2}))?$/.exec(digitado.trim());
  return m ? `${m[1] ?? ''}${m[2] ? `.${m[2]}` : ''}` : null;
}

/** Carrega uma consulta do computador e deixa recarregar. Falha mostra o texto em português simples. */
export function useConsulta<T>(
  ponte: PonteDoCorona,
  pedido: PedidoDoConsignado | null,
  dependencias: readonly unknown[],
): { dados: T | null; erro: string | null; recarregar: () => Promise<void> } {
  const [dados, setDados] = useState<T | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const chave = JSON.stringify(pedido);
  const recarregar = useCallback(async () => {
    if (!pedido) return;
    const r: Resposta<T> = await chamar<T>(ponte, pedido);
    if (r.ok) {
      setDados(r.dados);
      setErro(null);
    } else setErro(r.mensagem);
  }, [ponte, chave]);
  useEffect(() => {
    void recarregar();
  }, [recarregar, ...dependencias]);
  return { dados, erro, recarregar };
}

const FOTOS_PRONTAS = new Map<string, string>();

/** A foto do cliente ou do documento (guardada criptografada neste computador). Sem foto, a tela segue. */
export function FotoDoCliente({
  ponte,
  hash,
  rotulo,
  pequena = false,
}: {
  ponte: PonteDoCorona;
  hash: string | null;
  rotulo: string;
  pequena?: boolean;
}) {
  const [url, setUrl] = useState<string | null>(hash ? (FOTOS_PRONTAS.get(hash) ?? null) : null);
  useEffect(() => {
    if (!hash) {
      setUrl(null);
      return;
    }
    const pronta = FOTOS_PRONTAS.get(hash);
    if (pronta) {
      setUrl(pronta);
      return;
    }
    let ativo = true;
    void chamar<FotoDoClientePronta>(ponte, { operacao: 'consignado.fotoVer', hash }).then((r) => {
      if (!ativo || !r.ok || !r.dados.dataUrl) return;
      FOTOS_PRONTAS.set(hash, r.dados.dataUrl);
      setUrl(r.dados.dataUrl);
    });
    return () => {
      ativo = false;
    };
  }, [ponte, hash]);
  const classe = `consignado-foto${pequena ? ' consignado-foto--pequena' : ''}`;
  return url ? (
    <img className={classe} src={url} alt={rotulo} />
  ) : (
    <span className={`${classe} consignado-foto--vazia`}>{TEXTOS.consignado.clientes.semFoto}</span>
  );
}

/** A situação com o texto (a cor sozinha nunca basta) e a classe da cor da linha. */
export function situacaoNaTela(situacao: string): string {
  return TEXTOS.consignado.situacoes[situacao] ?? situacao;
}

/** Escolhe a classe de cor de uma linha pela situação (Em aberto branca, Pendente amarela, Fechado verde, Remarcado rosa). */
export function classeDaSituacao(situacao: string): string {
  return `consignado-linha consignado-linha--${situacao.toLowerCase().replace(/_/g, '-')}`;
}

/** A foto da peça (pequena); sem foto, o espaço "Sem foto". Nunca trava a leitura nem o acerto. */
export function FotoDoProduto({
  ponte,
  hash,
  nome,
}: {
  ponte: PonteDoCorona;
  hash: string | null;
  nome: string;
}) {
  const url = useFoto(ponte, hash, 'miniatura');
  return url ? (
    <img
      className="consignado-foto consignado-foto--pequena"
      src={url}
      alt={TEXTOS.comum.fotoDe(nome)}
    />
  ) : (
    <span className="consignado-foto consignado-foto--pequena consignado-foto--vazia">
      {TEXTOS.comum.semFoto}
    </span>
  );
}
