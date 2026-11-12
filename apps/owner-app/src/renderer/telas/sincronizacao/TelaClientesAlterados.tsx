import { Tabela } from '@corona/ui';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { TEXTOS_DE_SINCRONIZACAO } from '../../textos-sincronizacao';
import { dataEHora, lerValoresSubstituidos } from './dados';

const T = TEXTOS_DE_SINCRONIZACAO.clientes;

/** Dados de cliente que o vendedor mudou por cima de um valor que já estava lá (vale o último que chegou). */
export function TelaClientesAlterados({ ponte }: { ponte: PonteDoCorona }) {
  const busca = useBusca(
    () => chamar(ponte, 'sincronizacao.valoresSubstituidos'),
    'valores-trocados',
  );
  const trocas = busca.dados ? lerValoresSubstituidos(busca.dados) : [];
  return (
    <div className="gestao-tela">
      <h1 className="gestao-titulo">{T.titulo}</h1>
      <p className="gestao-texto">{T.ajuda}</p>
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      <Tabela
        legenda={T.legenda}
        textoVazio={T.vazio}
        colunas={[
          { chave: 'cliente', titulo: T.colunas.cliente },
          { chave: 'quem', titulo: T.colunas.quemMudou },
          { chave: 'quando', titulo: T.colunas.quando },
          { chave: 'dados', titulo: T.colunas.dados },
        ]}
        linhas={trocas.map((t) => ({
          cliente: t.clienteNome,
          quem: t.quemMudou,
          quando: dataEHora(t.quando),
          dados: (
            <ul className="gestao-lista-simples">
              {t.campos.map((c) => (
                <li key={c.campo}>
                  <strong>{T.campos[c.campo] ?? c.campo}</strong>: {T.era}{' '}
                  {c.valorAnterior ?? T.vazioNoCampo}; {T.passouASer}{' '}
                  {c.valorNovo ?? T.vazioNoCampo}
                </li>
              ))}
            </ul>
          ),
        }))}
      />
    </div>
  );
}
