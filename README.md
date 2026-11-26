# Hitchhike — Caronas Universitárias Seguras

**Projeto de Trabalho de Conclusão de Curso — Bacharelado em Sistemas de Informação | Centro Universitário Senac**

O Hitchhike é uma plataforma de mobilidade universitária que conecta estudantes interessados em compartilhar caronas, promovendo uma alternativa de deslocamento mais acessível, colaborativa e segura.

O projeto foi desenvolvido com foco na comunidade acadêmica do Senac Santo Amaro, utilizando autenticação institucional e mecanismos de segurança para proporcionar maior confiança entre motoristas e passageiros.

## Sobre o projeto

A proposta busca facilitar o deslocamento dos estudantes entre a instituição de ensino, estações de transporte público e outros pontos de interesse, incentivando o compartilhamento de veículos e contribuindo para a redução de deslocamentos individuais.

A plataforma é composta por duas partes integradas:

- **Aplicativo Android:** ambiente utilizado pelos estudantes para acessar as funcionalidades de caronas.
- **Painel administrativo:** ambiente responsável pelo gerenciamento, acompanhamento e validação de informações dos usuários.

## Funcionalidades

### Aplicativo Android
- Cadastro e autenticação com e-mail institucional.
- Verificação de e-mail e recuperação de senha.
- Gerenciamento de perfil de usuário.
- Cadastro e gerenciamento de caronas.
- Solicitação e gerenciamento de vagas.
- Controle de disponibilidade de vagas.
- Visualização de rotas e paradas intermediárias.
- Avaliações e sistema de reputação.
- Validação de elegibilidade para motoristas.
- Integração com Firebase.

### Painel administrativo
- Gerenciamento de usuários e motoristas.
- Recebimento e validação de documentos de CNH.
- Pré-análise documental com OCR.
- Controle de validade e renovação da CNH.
- Gerenciamento do ciclo de vida das caronas.
- Controle de solicitações e reputação.
- Integração com Firebase e Mercado Pago.

## Segurança

A segurança é um dos pilares do Hitchhike.

Entre os mecanismos implementados estão:

- Autenticação por e-mail institucional.
- Verificação de e-mail.
- Proteção de dados pessoais.
- Armazenamento protegido de documentos de CNH.
- Validação administrativa de motoristas.
- Verificação de elegibilidade e validade da CNH.
- Regras de segurança do Cloud Firestore.
- Testes automatizados de autorização e integridade.

## Tecnologias utilizadas

| Área | Tecnologias |
|---|---|
| Aplicativo | Kotlin, Android Studio |
| Arquitetura | MVVM, Clean Architecture |
| Banco local | Room |
| Backend | Node.js |
| Banco em nuvem | Firebase Firestore |
| Autenticação | Firebase Authentication |
| Documentos | OCR e processamento de PDF |
| Pagamentos | Mercado Pago |
| Testes | JUnit, Node.js Test Runner, Firebase Emulator |
| Versionamento | Git e GitHub |

## Estrutura do repositório

```text
Hitchhike-Senac/
│
├── app/                  # Aplicativo Android
│
├── admin/                # Backend e painel administrativo
│
├── gradle/               # Configurações do Gradle
│
├── README.md             # Documentação principal
│
├── build.gradle.kts      # Configuração do projeto
├── settings.gradle.kts   # Configuração dos módulos
└── gradlew               # Gradle Wrapper
```

## Como executar

### Aplicativo Android

1. Instale o Android Studio.
2. Clone este repositório.
3. Configure o projeto Firebase e o arquivo `google-services.json` localmente.
4. Abra o projeto no Android Studio.
5. Aguarde a sincronização do Gradle.
6. Execute o aplicativo em um dispositivo ou emulador Android.

### Backend administrativo

1. Instale o Node.js (preferencialmente versão 22 LTS).
2. Acesse a pasta `admin/`.
3. Instale as dependências com `npm install`.
4. Configure as variáveis de ambiente conforme o arquivo `.env.example`.
5. Execute os testes com `npm test`.
6. Consulte os scripts disponíveis em `package.json` para iniciar o servidor.

## Objetivo acadêmico

Este projeto foi desenvolvido como Trabalho de Conclusão de Curso do Bacharelado em Sistemas de Informação do Centro Universitário Senac.

A proposta está alinhada aos Objetivos de Desenvolvimento Sustentável (ODS), especialmente ao ODS 11 — Cidades e Comunidades Sustentáveis — e ao ODS 13 — Ação Contra a Mudança Global do Clima.

---

**Hitchhike — Compartilhando caminhos, conectando pessoas.**
