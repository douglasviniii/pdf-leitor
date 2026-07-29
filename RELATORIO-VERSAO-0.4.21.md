# Relatorio da versao 0.4.21

Data da validacao: 24/07/2026

## Objetivo

Esta versao corrige a abertura de PDFs grandes no Delvind PDF e melhora a
integracao do aplicativo com a opcao "Abrir com" do Windows.

## Funcionamento atual

O Delvind PDF 0.4.21:

- abre arquivos pelo botao "Abrir novo" ou pelo caminho recebido na linha de
  comando;
- permite definir o aplicativo como leitor padrao de arquivos `.pdf`;
- abre PDFs recebidos pelo SPED ECF;
- navega entre paginas e aplica zoom;
- pesquisa e destaca texto;
- permite grifar areas, desenhar, editar e apagar anotacoes;
- salva uma copia do documento alterado;
- imprime documentos;
- assina PDFs com certificado digital A1;
- converte arquivos pelos conversores disponiveis na interface;
- monitora downloads de PDF quando o recurso esta habilitado.

## Problema encontrado

Neste computador, ao abrir pelo SPED ECF um relatorio com 519 paginas, o
aplicativo podia exibir uma tela em branco ou falhar com a mensagem:

`GetMessage() failed. System error [1400] (O identificador da janela nao e valido)`

Havia dois fatores relevantes:

1. A tela criava antecipadamente um `Canvas` grande para cada pagina. Em um
   documento com centenas de paginas isso podia reservar mais de 1 GB antes
   mesmo da renderizacao.
2. O executavel empacotado podia ser iniciado pelo Windows ou pelo SPED com um
   diretorio de trabalho diferente do diretorio de instalacao.

## Correcao aplicada

- Os canvases das paginas agora nascem sem buffer e recebem dimensoes apenas
  quando a pagina e efetivamente renderizada.
- A renderizacao continua sendo feita sob demanda, priorizando as paginas
  proximas da pagina visivel.
- O total de paginas e atualizado antes da criacao das superficies visuais.
- A associacao de `.pdf` usa um inicializador que define corretamente o
  diretorio de trabalho do aplicativo.
- Em computadores onde o launcher do `jpackage` apresenta o erro 1400, o
  inicializador nativo `packaging/DelvindPdfNativeLauncher.cs` chama o
  `javaw.exe` diretamente e repassa o caminho completo do PDF.
- O script `packaging/Ensure-DelvindPdf.ps1` verifica o inicializador no login,
  restaura a copia corrigida quando necessario e reaplica a associacao de
  `.pdf` para o usuario atual.
- O monitor de downloads tambem e iniciado a partir do diretorio do
  executavel.
- Os scripts de compilacao usam o repositorio Maven local do projeto, quando
  disponivel.
- O instalador foi atualizado para a versao `0.4.21`.

## Validacao realizada

Foi testada a abertura do PDF temporario gerado pelo SPED ECF a partir da pasta
real do programa fiscal. O Delvind PDF abriu o documento e reconheceu as 519
paginas, sem permanecer na tela em branco.

## Compilacao

Requisitos:

- Java 21;
- Maven 3.9 ou superior.

Para compilar:

```powershell
.\build.ps1
```

Para executar em desenvolvimento:

```powershell
.\run.ps1
```

Para gerar o instalador MSI:

```powershell
.\release-windows-installer.ps1
```

Para compilar o inicializador nativo no Windows com o compilador do .NET
Framework:

```powershell
& "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe" `
  /nologo /target:winexe /optimize+ `
  /win32icon:packaging\app-icon.ico `
  /out:target\DelvindPdfNativeLauncher.exe `
  packaging\DelvindPdfNativeLauncher.cs
```

Os diretorios `target`, `dist-installer`, `jpackage-temp`, `package-input`,
`.m2-repository` e `tools` sao resultados locais ou dependencias de compilacao
e nao fazem parte do codigo-fonte publicado.
