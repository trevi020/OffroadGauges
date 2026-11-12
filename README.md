# Offroad Gauges per Android Auto

App Android che porta sul display di **Android Auto** i classici strumenti da fuoristrada in versione digitale:
temperatura esterna, bussola, orologio, altimetro con barometro e inclinometri di rollio e beccheggio.
Lo stile dei quadranti è ispirato alla strumentazione di un fuoristrada moderno (cielo notturno, cerchi scuri con
bordo oro, tacche bianche). Il progetto è indipendente e non ha alcun legame con case automobilistiche.

Tutti i dati vengono letti dal **telefono** (GPS, barometro, sensore di gravità): l'auto serve solo come schermo.

> Stato del progetto: funzionante e provato su Android Auto, ma pensato per uso personale e sperimentale.
> La versione corrente è indicata in `app/build.gradle.kts` (`versionName`).

---

## Cosa mostra

L'app disegna direttamente sulla Surface di Android Auto (come un'app di navigazione). Con lo schermo intero ci sono
due pagine affiancate, ciascuna con tre quadranti circolari; si passa dall'una all'altra con lo swipe o con le
schede in basso.

| Pagina | Quadrante | Dato |
|---|---|---|
| **Ambiente** | Temperatura | Temperatura esterna da internet, per la posizione GPS corrente |
| | Bussola | Direzione di marcia (media degli ultimi 3 secondi di GPS), con rotazione fluida |
| | Orologio | Orologio a lancette con data |
| **Assetto** | Altimetro | Quota sul livello del mare (GPS) e, sotto, la pressione barometrica |
| | Rollio | Inclinometro laterale a orizzonte artificiale |
| | Beccheggio | Inclinometro longitudinale a orizzonte artificiale |

Nei due inclinometri il terreno (colore e linea) e la vettura, stilizzata come una Jeep Willys, ruotano insieme
come un orizzonte artificiale. La scala va da -40° a +40°.

### Visualizzazione parziale (app affiancata ad altre)

Quando Android Auto affianca l'app ad altre, per esempio la musica, l'app riconosce lo spazio disponibile:

- **circa 2/3 dello schermo:** mostra **2 quadranti** scelti dall'utente;
- **circa 1/3 dello schermo:** mostra **1 quadrante** scelto dall'utente;
- in questi casi non ci sono pagine né schede.

I quadranti si scelgono nella schermata dell'app sul telefono, sezione "Visualizzazione parziale": qualsiasi
combinazione di 2 tra i 6 quadranti per la vista a 2/3, uno solo per la vista a 1/3. La scelta resta salvata.
Se tra quelli scelti c'è rollio o beccheggio, compare anche il pulsante **AZZERA 0°**.

---

## Come funziona

### Bussola
La direzione è la media vettoriale, pesata sulla velocità, dei rilevamenti GPS (bearing) degli ultimi 3 secondi,
considerati solo sopra circa 5 km/h. Da fermo mantiene l'ultima direzione nota. La lancetta è filtrata con uno
smorzatore del secondo ordine che simula uno strumento a bagno di glicerina: nessuno scatto e nessun rimbalzo.

### Temperatura esterna
Viene letta da [Open-Meteo](https://open-meteo.com) (gratuito, senza chiave API) alla posizione GPS corrente.
Si aggiorna ogni 10 minuti o dopo 10 km di spostamento, con nuovi tentativi ogni minuto finché manca il dato.
L'ultimo valore viene salvato e riutilizzato per qualche ora. Serve la connessione dati del telefono.

### Altimetro e pressione
- **Quota:** si usa l'altitudine sul livello del mare, in ordine di preferenza: quella fornita da Android 14 o
  successivi, il campo di quota della frase NMEA GGA, oppure la quota ellissoidale GPS meno 48 m
  (correzione di ripiego pensata per la Pianura Padana, costante `FALLBACK_GEOID_M` in `SensorHub.kt`).
  Il valore è filtrato e animato.
- **Pressione:** barometro del telefono, media mobile sugli ultimi 60 secondi, in hPa. Se il telefono non ha il
  barometro il valore resta vuoto.

### Inclinometri
Usano il sensore di **gravità** del telefono (fusione con il giroscopio), con ripiego sull'accelerometro filtrato.

**Posizionamento del telefono**: piatto, schermo verso l'alto, **lato lungo parallelo all'asse dell'auto** e bordo
superiore verso il parabrezza (come sulla maggior parte dei piani di ricarica e dei supporti). Se è girato di 180°
si attiva l'opzione nell'app. Nell'app sul telefono c'è una guida animata che mostra come appoggiarlo.

Convenzione dei segni: rollio positivo = lato destro in basso, beccheggio positivo = muso in alto.

**Azzeramento:** a telefono fermo e auto in piano si preme **AZZERA 0°**, dallo schermo di Android Auto (pagina
Assetto) oppure dall'app sul telefono. L'azzeramento viene rifiutato se l'auto si sta muovendo o se il telefono è
inclinato di oltre 25°. Il valore resta salvato.

### Interfaccia su Android Auto
- L'app è un'app di **navigazione** (Car App Library, `NavigationTemplate`) perché è l'unico tipo che dà accesso a
  una Surface su cui disegnare liberamente.
- Lo **swipe** richiede il pulsante "Pan" nel template, che sui touchscreen non viene mostrato. Sulle unità senza
  touch resta il cambio pagina con la schermata o con il pulsante nella barra azioni.
- Il template richiede almeno un'azione nella barra in alto: ce n'è una sola, con icona trasparente, che cambia
  pagina.
- Sfondo con dithering: il gradiente scuro viene costruito con un leggero rumore per evitare le fasce
  (banding) che comparirebbero con la compressione video di Android Auto.

---

## Requisiti

- Telefono Android 8.0 o successivo (`minSdk 26`), con GPS. Barometro e sensore di gravità consigliati.
- App **Android Auto** installata e un'auto compatibile, oppure il Desktop Head Unit.
- Per compilare: Android Studio (Koala o successivo) con JDK 17 o 21. Il progetto usa Gradle 8.7, Android Gradle
  Plugin 8.5.2, Kotlin 1.9.24 e `androidx.car.app:app:1.4.0`.

---

## Installazione

### 1. Compilare dai sorgenti

1. Clona il repository e aprilo in Android Studio. La sincronizzazione Gradle scarica da sola le dipendenze.
   Se Android Studio segnala un JDK non compatibile (per esempio il 25), seleziona il JDK 21 integrato.
2. Collega il telefono con il debug USB attivo e premi **Run**, oppure da terminale:

```
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
```

   L'APK si trova in `app/build/outputs/apk/debug/app-debug.apk`.

### 2. Installare un APK dalle Releases
Scarica l'APK dalla sezione **Releases** del repository e installalo sul telefono, consentendo l'installazione da
questa origine. Se passi da una versione di debug a una firmata, o viceversa, disinstalla prima l'app: le firme
sono diverse.

### 3. Abilitare le app fuori dal Play Store in Android Auto
1. In Android Auto tocca 10 volte "Versione" per attivare le **Impostazioni sviluppatore**.
2. Dal menu in alto a destra apri *Impostazioni sviluppatore* e attiva **Origini sconosciute**.

### 4. Perché l'app può non comparire nell'elenco dell'auto

Su un'auto reale, un'app installata a mano può risultare nell'elenco delle app di Android Auto ma non comparire in
*Personalizza elenco app*. Può anche partire da sola come ultima app usata e poi non essere più richiamabile se
scegli un'altra app di navigazione. Sul Desktop Head Unit invece funziona.

La spiegazione più plausibile, ricavata da un'analisi della comunità
([Fermata, issue #846](https://github.com/AndreyPavlenko/Fermata/issues/846), riferita ad Android Auto 17.7 e non
verificata ufficialmente da Google), è che Android Auto controlli quale **installatore** risulta registrato per
l'app. "Origini sconosciute" varrebbe per le app multimediali ma non per quelle a template, come questa.

Soluzioni, in ordine di pulizia:

1. **Play Store:** pubblicare l'app almeno nel canale di test interno della Play Console. Per i test personali è
   la strada più solida, ma richiede un account sviluppatore.
2. **Installazione con installatore del Play Store registrato.** Su un telefono dell'autore ha funzionato
   installando l'APK con **KingInstaller** e **Shizuku** (variante ShizukuNext), che registrano il Play Store come
   installatore. Sono strumenti di terze parti: usali solo se capisci cosa fanno, a tuo rischio. L'installatore
   registrato si perde se l'app viene aggiornata in un altro modo, e in quel caso l'app può sparire di nuovo.
   Per controllare l'installatore registrato:

```
adb shell dumpsys package com.offroadgauges | grep -E "installerPackageName|initiatingPackageName"
```

   Deve risultare `installerPackageName=com.android.vending`.
3. **Desktop Head Unit** di Google, per provare senza auto: non applica questi controlli.

### 5. Primo avvio
Apri l'app sul telefono, concedi i permessi (posizione e notifiche) e, per un funzionamento stabile con lo schermo
spento, la posizione **"Consenti sempre"**. Poi collega il telefono all'auto, apri l'elenco app e scegli
**Offroad Gauges**.

---

## Struttura del progetto

```
OffroadGauges/
├── build.gradle.kts            # plugin Gradle e Kotlin
├── settings.gradle.kts
├── gradle.properties
├── gradlew, gradlew.bat        # wrapper Gradle
├── keystore.properties.example # modello per i dati di firma (il file reale non va versionato)
└── app/
    ├── build.gradle.kts        # configurazione dell'app, dipendenze, firma release
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/                # icone, stringhe, automotive_app_desc.xml
        └── java/com/offroadgauges/
            ├── car/
            │   ├── GaugeCarAppService.kt   # servizio Car App Library (entry point per Android Auto)
            │   ├── GaugeSession.kt         # sessione: avvia sensori e servizio in primo piano
            │   ├── GaugeScreen.kt          # NavigationTemplate, Surface, tocchi, swipe, area visibile
            │   └── PermissionScreen.kt     # richiesta del permesso di posizione dall'auto
            ├── core/
            │   ├── SensorHub.kt            # GPS, NMEA, barometro, inclinazione, azzeramento
            │   ├── TelemetryService.kt     # foreground service di tipo "location"
            │   ├── WeatherRepo.kt          # temperatura da Open-Meteo
            │   ├── Damper.kt               # smorzatore "a glicerina" per lancette e valori
            │   └── Prefs.kt                # impostazioni salvate (azzeramento, quadranti scelti, ecc.)
            ├── render/
            │   └── GaugeRenderer.kt        # disegno dei quadranti, pagine, modalità 1/3 - 2/3 - intero
            └── ui/
                ├── MainActivity.kt         # schermata sul telefono: permessi, guida, scelta quadranti
                └── PlacementGuideView.kt   # animazione che mostra come posare il telefono
```

---

## Compilare una release firmata

1. Genera un keystore **fuori** dalla cartella del progetto e fanne subito una copia di backup:

```
keytool -genkeypair -v -keystore offroadgauges.jks -alias offroadgauges -keyalg RSA -keysize 2048 -validity 10000
```

   `keytool` è nel JDK di Android Studio, cartella `jbr/bin`.
2. Copia `keystore.properties.example` in `keystore.properties` (nella cartella principale del progetto) e compila i
   valori. Il file è escluso da git: non va mai pubblicato.
3. Costruisci la release: *Build > Generate Signed App Bundle / APK*, oppure `./gradlew assembleRelease`.
   L'APK si trova in `app/build/outputs/apk/release/`.

Se `keystore.properties` non esiste, la build release esce non firmata e la build di debug funziona come sempre.
Non perdere il keystore né le password: senza non si possono installare aggiornamenti sopra una versione già
installata.

---

## Limiti noti

- Gli inclinometri risentono delle vibrazioni se il telefono non è fermo nel supporto: lo smorzamento stabilizza
  gli aghi ma su fondi molto sconnessi le letture restano indicative.
- La direzione della bussola arriva dal GPS: sotto circa 5 km/h non si aggiorna. Non usa la bussola magnetica.
- La quota GPS ha un'incertezza di alcuni metri. La correzione di ripiego di 48 m vale per la Pianura Padana: in
  altre zone conviene regolarla in `SensorHub.kt` oppure fare affidamento su Android 14 o successivi.
- La temperatura richiede la rete dati e mostra il valore della stazione meteo più vicina, non un sensore dell'auto.
- Il tocco sullo schermo e lo swipe funzionano solo su unità touch.
- Il riconoscimento della visualizzazione a 1/3 e 2/3 si basa sull'area visibile comunicata da Android Auto: su
  alcune auto potrebbe servire regolare le soglie in `GaugeRenderer.kt`.
- Il servizio Car App accetta qualsiasi host (`HostValidator.ALLOW_ALL_HOSTS_VALIDATOR`): va bene per uso
  personale, per una distribuzione pubblica va sostituito con un elenco di host fidati.

---

## Licenza

Distribuito con licenza MIT: vedi il file [LICENSE](LICENSE).
