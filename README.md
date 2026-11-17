# Diktuj

Diktování v češtině pro Arch Linux a Android.

- **Telefon** jen nahrává (i se zamčenou obrazovkou) a po nahrávce sám pošle zvuk přes domácí Wi-Fi na Arch.
- **Arch** zvuk přijme, zeptá se okénkem „Přepsat teď?“ a po jednom kliknutí ho přepíše lokálně
  (`whisper-ctranslate2`, zdarma, offline).
- Na Archu jde nahrávat i přímo klávesovou zkratkou.

Texty: `~/Diktovani/RRRR-MM-DD_HH-MM-SS.txt`. Audio zůstává v `~/.local/share/diktuj/sessions/`.

```
telefon ──(Wi-Fi, HTTP + token)──▶ diktuj receive ──▶ okno „Přepsat teď?“ ──▶ whisper ──▶ ~/Diktovani/*.txt
```

## 1) Arch: instalace

```bash
sudo pacman -S --needed ffmpeg tk libnotify     # tk = okno "Přepsat teď?", libnotify = notifikace
mkdir -p ~/.local/bin ~/.config/diktuj ~/.config/systemd/user
cp arch/diktuj ~/.local/bin/diktuj && chmod +x ~/.local/bin/diktuj
cp arch/diktuj-receiver.service ~/.config/systemd/user/

diktuj token            # vytvoří token a vypíše adresu (IP:port) pro telefon
systemctl --user enable --now diktuj-receiver
systemctl --user status diktuj-receiver
```

`whisper-ctranslate2` už máte (z `prepis.sh`). Výchozí model je `small`, na CPU, s nejnižší prioritou
(`nice`/`ionice`), takže se u toho dá pracovat. Nastavení je v `~/.config/diktuj/config.ini`:

```ini
[diktuj]
backend = local            # local | groq | auto  (groq/auto potřebují api_key, viz níže)
whisper_model = small      # medium je přesnější a pomalejší
whisper_threads = 4
confirm = dialog           # dialog = zeptá se okénkem | auto = přepíše hned | notify = jen upozorní
listen_port = 8765
```

Pokud má vaše síť firewall, který blokuje příchozí spojení, povolte port 8765 jen z domácí sítě, např. v
`/etc/nftables.conf` do pravidel `input`: `tcp dport 8765 ip saddr 192.168.0.0/16 accept`.

Okno „Přepsat teď?“ potřebuje, aby služba znala váš displej. Unit má `DISPLAY=:0` a
`XAUTHORITY=~/.Xauthority`. Pokud používáte jiný displej, upravte hodnoty v
`~/.config/systemd/user/diktuj-receiver.service`. Kdyby okno nešlo zobrazit, přijde notifikace a
nahrávku přepíšete příkazem `diktuj inbox`.

### Nahrávání přímo na Archu

Zkratka přepíná start/stop (sxhkd: `super + d` → `diktuj`; i3: `bindsym $mod+d exec --no-startup-id diktuj`;
dwm: `{ MODKEY, XK_d, spawn, SHCMD("diktuj") }`). Po zastavení se přepis spustí sám, bez dotazu.

Další příkazy: `diktuj status`, `diktuj inbox` (projde nepřepsané a u každé se zeptá), `diktuj retry`
(přepíše vše nepřepsané bez ptaní).

## 2) Android: sestavení APK přes GitHub (nic neinstaluješ)

1. Na GitHubu vytvoř **soukromý** repozitář, např. `diktuj`.
2. Z této složky:
   ```bash
   git init -b main && git add . && git commit -m "Diktuj"
   git remote add origin git@github.com:dzanker361/diktuj.git
   git push -u origin main
   ```
3. Na GitHubu: **Actions** → běh **Build APK** (pár minut) → dole **Artifacts** → `diktuj-apk`.
4. ZIP rozbal, `app-debug.apk` pošli do telefonu přes KDE Connect a nainstaluj
   (povolit instalaci z neznámých zdrojů).
5. Kdyby sestavení selhalo, pošli mi log z Actions.

Aktualizace: `git push`, stáhnout nový APK, nainstalovat přes starý. Podpis je pevný, nastavení zůstane.

## 3) Telefon: nastavení

1. V aplikaci **Diktuj** vyplň **Adresu** (z `diktuj token`, např. `192.168.1.50:8765`) a **Token**.
   Token zkopíruj na Archu a vlož v telefonu, schránka KDE Connect to přenese.
2. Klepni **Test spojení s Archem** (musí být na stejné Wi-Fi a běžet `diktuj-receiver`).
3. Klepni **●  Začít nahrávat**, povol mikrofon a notifikace.
4. Jednorázově na Xiaomi: **1) Výjimka z úspory baterie**, **2) Autostart**; v Nastavení → Aplikace → Diktuj →
   Úspora baterie = **Bez omezení**; v přehledu posledních aplikací kartu Diktuj zamkni.
5. Zkouška: nahrávej, zamkni telefon na minutu, odemkni a zastav. Na Archu vyskočí „Přepsat teď?“.

Zastavit jde v aplikaci, v notifikaci nebo dlaždicí v rychlém nastavení (přidat: tažení dolů → Upravit → Diktuj).
Klepnutí na dlaždici aplikaci krátce otevře, spustí nahrávání a hned se zavře.

**Odesílání:** po zastavení se nahrávka pošle, jakmile je telefon na neměřené Wi-Fi a Arch odpovídá.
Když je Arch vypnutý nebo jste mimo domov, zkouší to telefon každých pár minut, dokud se to nepovede.
Tlačítko **Odeslat na Arch teď** to spustí ručně a ukáže důvod případné chyby. Odeslané nahrávky se z telefonu
mažou po 14 dnech. Pokud označíte Wi-Fi jako „měřenou“, automatické odesílání nepoběží, použijte ruční tlačítko.

**Bezpečnost:** komunikace v domácí síti je nešifrované HTTP chráněné tokenem. Nepoužívejte pro citlivé nahrávky
v cizích sítích a port 8765 nevystavujte do internetu.

## Volitelně: Groq místo lokálního přepisu

Pro rychlejší a přesnější přepis (Whisper large-v3) zdarma bez karty: https://console.groq.com → API Keys →
`api_key = gsk_...` do `config.ini` a `backend = auto` (Groq, při chybě nebo limitu lokálně) nebo `groq`.
Bezplatný tarif: 7 200 s audia za hodinu, 28 800 s za den, soubor max 25 MB, bez záruky dostupnosti.
