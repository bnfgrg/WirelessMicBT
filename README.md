# Microfono Wireless BT

App Android che trasforma il telefono in un microfono wireless, instradando l'audio verso un altoparlante Bluetooth tramite profilo **SCO/Hands-Free** (lo stesso usato per le chiamate), con cancellazione dell'eco acustica (AEC), soppressione del rumore (NS) e controllo automatico del guadagno (AGC) applicati alla cattura del microfono.

## Come funziona

1. Premi "AVVIA MICROFONO"
2. L'app forza il telefono in modalità chiamata (`MODE_IN_COMMUNICATION`) e richiede l'instradamento SCO verso l'altoparlante Bluetooth già accoppiato
3. Una volta connesso lo SCO, parte la cattura microfono (`AudioSource.VOICE_COMMUNICATION`) con AEC/NS/AGC attivati se il dispositivo li supporta
4. L'audio viene riprodotto in streaming continuo sull'altoparlante

## Requisiti per il funzionamento

- L'altoparlante Bluetooth deve essere **già accoppiato** nelle impostazioni Bluetooth del telefono prima di avviare l'app
- L'altoparlante deve supportare il profilo **HFP (Hands-Free Profile)**, non solo A2DP — molti speaker Bluetooth economici supportano solo A2DP e in quel caso lo SCO non si connetterà (l'app mostrerà "Errore: nessun dispositivo SCO")
- L'AEC hardware non è garantito su tutti i telefoni: l'app mostra un badge "AEC"/"NS" verde solo se il dispositivo lo supporta realmente

## Limiti noti

- Qualità audio bassa (banda stretta, tipica delle chiamate telefoniche, non hi-fi)
- Se l'altoparlante non supporta HFP, questa app non può funzionare con quel modello — serve un altoparlante Bluetooth con funzione vivavoce/chiamate
- L'efficacia dell'AEC dipende dall'hardware del telefono; su dispositivi senza AEC hardware il rischio di feedback rimane, va gestito tenendo il telefono lontano dall'altoparlante

## Build automatica (GitHub Actions)

1. Crea un repository GitHub e carica questi file
2. Vai su **Actions** → il workflow "Build Android APK" parte automaticamente al push su `main`/`master` (oppure avvialo manualmente da "Run workflow")
3. A build completata, scarica l'APK dalla sezione **Artifacts** dell'esecuzione del workflow
4. Trasferisci l'APK sul telefono e installalo (serve abilitare "Origini sconosciute" per l'app usata per il trasferimento)

## Permessi richiesti

- Microfono (RECORD_AUDIO)
- Bluetooth (BLUETOOTH_CONNECT su Android 12+)
- Notifiche (per il servizio in primo piano, obbligatorio per la cattura audio continua)
