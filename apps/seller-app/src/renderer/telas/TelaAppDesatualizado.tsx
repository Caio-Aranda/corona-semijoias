import { Aviso, CabecalhoDeMarca } from '@corona/ui';
import { TEXTOS } from '../textos';
import { useIdioma } from '../idioma';
import { SeletorDeIdioma } from '../SeletorDeIdioma';

/**
 * Sem botão "Atualizar agora": ele só existe com a atualização automática (Fase 9, glossário parte 4).
 * O texto é o que a API devolveu; se não veio nenhum, vale o do glossário.
 */
export function TelaAppDesatualizado({ mensagem }: { mensagem: string }) {
  const idioma = useIdioma();
  return (
    <div className="vendas-pagina">
      <CabecalhoDeMarca nomeDoApp="Corona Vendas" acoes={<SeletorDeIdioma />} />
      <main className="vendas-conteudo">
        <div className="vendas-cartao">
          {idioma === 'pt' && mensagem ? (
            <Aviso tipo="atencao">{mensagem}</Aviso>
          ) : (
            <Aviso tipo="atencao" titulo={TEXTOS.desatualizado.titulo}>
              {TEXTOS.desatualizado.texto}
            </Aviso>
          )}
        </div>
      </main>
    </div>
  );
}
