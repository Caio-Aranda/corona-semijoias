import { Aviso, Botao, Campo } from '@corona/ui';
import { useState, type FormEvent } from 'react';
import type { PonteDoCorona, ResultadoDeEntrada } from '../../shared/ponte';
import { TEXTOS } from '../textos';
import { MensagemDeEntrada } from './MensagemDeEntrada';

export interface TelaEntrarProps {
  ponte: PonteDoCorona;
  /** A sessão acabou sozinha: a tela explica antes de pedir a senha de novo. */
  sessaoTerminou?: boolean;
  /** Sem internet e com dados neste computador: entra só com a senha (tarefa 5.8). */
  semInternet?: boolean;
  /** Chamado quando é preciso ver o novo estado (entrou, senha temporária, aplicativo desatualizado). */
  aoMudar: () => void;
}

export function TelaEntrar({
  ponte,
  sessaoTerminou = false,
  semInternet = false,
  aoMudar,
}: TelaEntrarProps) {
  const [usuario, setUsuario] = useState('');
  const [senha, setSenha] = useState('');
  const [enviando, setEnviando] = useState(false);
  const [resultado, setResultado] = useState<ResultadoDeEntrada | null>(null);

  async function entrar(evento: FormEvent) {
    evento.preventDefault();
    if (enviando || !usuario.trim() || !senha) return;
    setEnviando(true);
    setResultado(null);
    try {
      const r = await ponte.entrar({ usuario: usuario.trim(), senha });
      if (r.tipo === 'entrou' || r.tipo === 'trocar-senha' || r.tipo === 'app-desatualizado') {
        setSenha('');
        aoMudar();
        return;
      }
      setResultado(r);
    } catch {
      setResultado({ tipo: 'erro-inesperado' });
    } finally {
      setEnviando(false);
    }
  }

  return (
    <div className="cs-entrada-pagina">
      <main>
        <form className="cs-entrada" onSubmit={(e) => void entrar(e)} noValidate>
          <span className="cs-entrada__logo" role="img" aria-label="Corona Semijoias" />
          <p className="cs-entrada__titulo">Corona Vendas</p>
          <h1 className="cs-entrada__titulo-pagina">
            {semInternet ? TEXTOS.semInternetNaEntrada.titulo : 'Entrar'}
          </h1>
          {semInternet ? <p className="vendas-texto">{TEXTOS.semInternetNaEntrada.texto}</p> : null}
          {sessaoTerminou ? (
            <Aviso tipo="atencao" titulo={TEXTOS.sessaoTerminou.titulo}>
              {TEXTOS.sessaoTerminou.texto}
            </Aviso>
          ) : null}
          <MensagemDeEntrada resultado={resultado} />
          <Campo
            rotulo="Usuário"
            value={usuario}
            onChange={(e) => setUsuario(e.target.value)}
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            autoFocus
          />
          <Campo
            rotulo="Senha"
            type="password"
            value={senha}
            onChange={(e) => setSenha(e.target.value)}
            autoComplete="current-password"
          />
          <Botao type="submit" variante="primario" grande disabled={enviando}>
            Entrar
          </Botao>
        </form>
      </main>
    </div>
  );
}
