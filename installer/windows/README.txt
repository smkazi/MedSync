================================================================================
 MedSync - one-click install for Windows
================================================================================

 MedSync is a hospital management platform: twelve back-end services, a browser
 application and one PostgreSQL database. This folder installs and runs all of
 it on your own machine. Nothing leaves the machine and no account is created
 anywhere.


--------------------------------------------------------------------------------
 1. RUN IT
--------------------------------------------------------------------------------

 Double-click              MedSync-Setup.exe
 On an ARM machine         MedSync-Setup-arm64.exe

 A black console window opens and stays open. That window is the log: the first
 install builds fourteen Java modules and a web application, so expect ten to
 twenty-five minutes, most of it silent while Maven and npm download. Leave it
 alone until it says "MedSync is running" and your browser opens.

 WINDOWS WILL WARN YOU FIRST. "Windows protected your PC" appears because this
 file is not code-signed - a signing certificate has to be bought and held by a
 company, and this has neither. Click "More info", then "Run anyway". If you are
 not willing to do that, do not run it; that is a reasonable position and the
 alternative is below under BUILD IT YOURSELF.


--------------------------------------------------------------------------------
 2. WHAT IT NEEDS, AND WHAT IT WILL ASK
--------------------------------------------------------------------------------

 Required        Java 21 or newer, Node 22 or newer
 For a database  PostgreSQL, OR Docker Desktop, OR a server you already have
 Not required    Maven - a wrapper is included and used automatically
 Not required    git - the source is downloaded over HTTPS

 Anything missing is listed by name with the exact command to install it, and
 then it ASKS before installing anything. Say no and nothing happens. Everything
 it installs comes from winget, which means the vendors' own packages, and every
 one of them can be removed later with "winget uninstall".

 If Docker Desktop is installed AND running, that route is used instead and
 needs no Java, no Node and no PostgreSQL at all.

 Check the machine without installing anything:

     MedSync-Setup.exe doctor


--------------------------------------------------------------------------------
 3. USING THE APP
--------------------------------------------------------------------------------

 Web application     http://localhost:3000
 Corridor display    http://localhost:3000/display/GF-GEN
                     (no sign-in - that screen carries no patient information
                     at all, by design, because it hangs in a public corridor)

 Sign in with any of the accounts below. The password for every one of them is:

     ChangeMe!Dev2026

   admin            everything, including the audit trail
   dr.rao           a doctor - charts, orders tests, prescribes
   nurse.iqbal      a nurse - triage, the casualty board, the drug round
   reception        the front desk - registers and books, cannot open a chart
   lab.tech         collects specimens and enters results, cannot verify them
   dr.pathan        a pathologist - verifies and releases results
   pharmacist       dispenses and keeps stock, cannot open a chart
   cashier          invoices and payments, cannot open a chart
   radiographer     runs the scanner worklist, cannot report on it
   dr.mistry        a radiologist - reports and signs, cannot order the scan
   epidemiologist   aggregate rates and notifiable counts, nothing per-patient
   new.starter      still on its first password, so it can only change it

 Those restrictions are enforced by the services, not by hiding menu items.
 Sign in as the cashier, type a chart address into the browser, and you get a
 403 rather than a page. That is the point of having twelve accounts.

 A GOOD FIRST WALK THROUGH IT

   1. Sign in as reception. Patients > Register a patient. Fill it in.
   2. Scheduling > Book an appointment for that patient with Dr Rao.
   3. Sign out. Sign in as dr.rao. Dashboard shows the clinic list.
      Open the appointment, record vitals, write a note, add a diagnosis,
      and order a blood test from the same screen.
   4. Sign in as lab.tech. Laboratory shows the order. Collect the specimen,
      then enter results.
   5. Sign in as dr.pathan and verify them. That releases the report.
   6. Back as dr.rao, the result is on the chart with its reference ranges.
   7. Sign in as nurse.iqbal. Clinical > Casualty board - triage somebody in
      and admit them to a bed. The board sorts by how sick people are, not by
      who arrived first.
   8. Sign in as cashier. Billing shows what the visit generated.

 Empty screens on a fresh install are correct, not broken: there are no patients
 until you create one. Only the facility - floors, rooms, beds - is pre-loaded.


--------------------------------------------------------------------------------
 4. THE OTHER COMMANDS
--------------------------------------------------------------------------------

 Open a Command Prompt in this folder (Shift + right-click > "Open in
 Terminal") and run:

   MedSync-Setup.exe            install if needed, start, check, open a browser
   MedSync-Setup.exe doctor     what this machine has and what it is missing
   MedSync-Setup.exe status     what is running, and on which port
   MedSync-Setup.exe smoke      sign in and read one screen from every service
   MedSync-Setup.exe down       stop everything
   MedSync-Setup.exe uninstall  stop everything and delete what it created
   MedSync-Setup.exe fetch      download the source only, without building
   MedSync-Setup.exe db         provision a database only, and print its URL

 After a restart of your computer, nothing is running: double-click the .exe
 again. The second start skips the build and takes about a minute.


--------------------------------------------------------------------------------
 5. WHERE THINGS GO, AND REMOVING IT
--------------------------------------------------------------------------------

 Everything lives in one folder:

     %LOCALAPPDATA%\MedSync

 That holds the downloaded source, the database, the log files and one generated
 encryption key. "MedSync-Setup.exe uninstall" stops the platform and deletes
 all of it, after asking. Anything installed through winget - Java, Node,
 PostgreSQL - stays installed, because it belongs to your machine rather than to
 MedSync; remove those with "winget uninstall" if you want them gone.

 Nothing is written to the registry. No service is registered. No scheduled task
 is created. Nothing starts at boot.


--------------------------------------------------------------------------------
 6. IF SOMETHING GOES WRONG
--------------------------------------------------------------------------------

 The logs are the first place to look:

     %LOCALAPPDATA%\MedSync\logs

 One file per service, plus web.log and the Maven and npm build output.

 "N port(s) are occupied"
     Something else is on port 3000 or 8080-8091. Run
     "MedSync-Setup.exe down" if a previous run left them, or close whatever
     is using them.

 "no way to get a PostgreSQL"
     Install PostgreSQL (winget install PostgreSQL.PostgreSQL.16), or start
     Docker Desktop, or point it at a server you already have:
         set HMS_DB_URL=jdbc:postgresql://host:5432/hms

 "this Docker engine is in Windows-container mode"
     Right-click the Docker tray icon and choose "Switch to Linux containers".

 The build failed
     Read the end of web-build.log or the console output. A build that runs out
     of memory or loses its network connection mid-download usually succeeds on
     a second attempt, because both Maven and npm resume from what they have.

 A service did not come up
     Its log names the reason. The most common one on a first install is a
     database that exists but has no pg_trgm and btree_gist extension; the
     console says which command to run.


--------------------------------------------------------------------------------
 7. BUILD IT YOURSELF
--------------------------------------------------------------------------------

 If you would rather not run somebody else's executable - and you should not
 have to - the source of this installer is a few hundred lines of Go in
 installer/windows/, and the build is reproducible:

     git clone https://github.com/smkazi/MedSync
     cd MedSync
     go build -trimpath -ldflags "-s -w" -o MedSync-Setup.exe ./installer/windows

 That produces the same bytes as the file in this folder. On Linux or macOS
 there is no need for any of this - use ./medsync.sh up instead.


--------------------------------------------------------------------------------
 8. WHAT THIS IS NOT
--------------------------------------------------------------------------------

 A development and demonstration install. The accounts above and their shared
 password are published in the source, event delivery runs through the database
 rather than a broker, and TLS is off. Do not put real patient data in it.

 The project's own README covers what is built, what is deliberately not, and
 what every test suite found:

     https://github.com/smkazi/MedSync
