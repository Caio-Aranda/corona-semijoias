import { temAlteracoesNaoSalvas } from '../nucleo/guarda-de-saida';
import { TEXTOS_DE_IDIOMA } from '../textos-idioma';
import { definirIdioma, IDIOMAS, type Idioma } from './idioma';
import { useIdioma } from './useIdioma';

/** O nome de cada idioma, sempre na língua dele (assim a pessoa acha o seu mesmo estando no outro). */
const NOME_COMPLETO: Record<Idioma, string> = { es: 'Español', pt: 'Português' };

/**
 * Troca o idioma: dois botões pequenos, "ES" e "PT", o atual destacado. Quem lê a tela por voz ouve o nome completo.
 * Os textos de toda a tela são recarregados no ato (a tela reabre no mesmo lugar); se houver algo por salvar,
 * pergunta antes.
 */
export function SeletorDeIdioma({ aoTrocar }: { aoTrocar?: () => void }) {
  const atual = useIdioma();

  function escolher(novo: Idioma) {
    if (novo === atual) return;
    if (temAlteracoesNaoSalvas() && !window.confirm(TEXTOS_DE_IDIOMA.alteracoesNaoSalvas)) return;
    definirIdioma(novo);
    (aoTrocar ?? recarregarATela)();
  }

  return (
    <div className="gestao-idioma" role="group" aria-label={TEXTOS_DE_IDIOMA.rotulo}>
      {IDIOMAS.map((i) => (
        <button
          key={i}
          type="button"
          className="gestao-idioma__botao"
          aria-pressed={i === atual}
          aria-label={NOME_COMPLETO[i]}
          title={NOME_COMPLETO[i]}
          onClick={() => escolher(i)}
        >
          {i.toUpperCase()}
        </button>
      ))}
    </div>
  );
}

/** Reabre a tela no mesmo endereço: todo texto, relatório e data passa a sair no idioma novo. */
function recarregarATela() {
  window.location.reload();
}
