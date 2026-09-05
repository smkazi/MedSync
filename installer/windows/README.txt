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

 A black console window opens and stays open. That window is the log. The first
 run unpacks the runtime out of the executable - about a minute - and then starts
 twelve services, the web application and the database. Expect three to five
 minutes the first time and under a minute after that. Leave it alone until it
 says "MedSync is running" and your browser opens.

 It does not build anything, download anything, or install anything on this
 machine. Everything it needs is inside the file you just double-clicked.

 WINDOWS WILL WARN YOU FIRST. "Windows protected your PC" appears because this
 file is not code-signed - a signing certificate has to be bought and held by a
 company, and this has neither. Click "More info", then "Run anyway". If you are
 not willing to do that, do not run it; that is a reasonable position and the
 alternative is below under BUILD IT YOURSELF.


--------------------------------------------------------------------------------
 2. WHAT IT NEEDS: NOTHING
--------------------------------------------------------------------------------

 Required        Windows 10 or 11, 64-bit. 8 GB of memory. 2 GB of free disk.
 Not required    Java, Node, Maven, PostgreSQL, Python, Docker, git
 Not required    a network connection, once you have the file

 All of those are inside the executable:

     a Java runtime           Temurin 21, trimmed to what the services load
     a Node runtime           Node 22
     a PostgreSQL server      version 16
     an embeddable Python     3.11, for the clinical decision-support service
     every library            172 Java libraries, one copy of each
     the whole application    twelve services, the web app, the AI service
     every licence            in licenses\ beside the unpacked runtime

 Nothing is installed into Windows. No registry keys, no Program Files, no
 services, no PATH changes. Everything lives in one folder that "uninstall"
 deletes.

 See what is inside the file, and whether the ports are free:

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
   MedSync-Setup.exe db         provision a database only, and print its URL

 After a restart of your computer, nothing is running: double-click the .exe
 again. The second start reuses the unpacked runtime and takes about a minute.


--------------------------------------------------------------------------------
 5. WHERE THINGS GO, AND REMOVING IT
--------------------------------------------------------------------------------

 Everything lives in one folder:

     %LOCALAPPDATA%\MedSync

 That holds the unpacked runtime, the database, the log files and one generated
 encryption key. "MedSync-Setup.exe uninstall" stops the platform and deletes
 all of it, after asking. Nothing was installed outside that folder, so deleting
 it leaves the machine exactly as it was before you double-clicked the file -
 there is no second thing to uninstall afterwards.

 Nothing is written to the registry. No service is registered. No scheduled task
 is created. Nothing starts at boot.


--------------------------------------------------------------------------------
 6. IF SOMETHING GOES WRONG
--------------------------------------------------------------------------------

 The logs are the first place to look:

     %LOCALAPPDATA%\MedSync\logs

 One file per service, plus web.log, ai-service.log and postgres.log.

 "N port(s) are occupied"
     Something else is on port 3000 or 8080-8091. Run
     "MedSync-Setup.exe down" if a previous run left them, or close whatever
     is using them.

 "no way to get a PostgreSQL"
     The bundled server would not start. Read postgres.log, or point this at
     Docker Desktop, or point it at a server you already have:
         set HMS_DB_URL=jdbc:postgresql://host:5432/hms

 "this Docker engine is in Windows-container mode"
     Right-click the Docker tray icon and choose "Switch to Linux containers".

 Something did not start
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
