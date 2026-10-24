# SoarLog
A pilot flight logbook for gliding.

## Why:
I have been using a Memento database to record my flights. It works very well but it uses a cloud service to sync data and the export option is adequate, but not great. The free version has limitations on filtering that were a minor nuisance.  
I enter my flights into Memento when I finish flying, then transfer to my paper logbook sometime later. 

I wouldn't mind dropping the paper logbook if my digital one is compliant with local rules and sufficiently robust.

I don't see the need to pay for something just to keep a simple record of flights.  
Using a hosted service or an app that doesn't make it easy to move my data elsewhere is not on my agenda at all!

Yes, this is all about me. But if it's useful to you, all the better.

## What it does:
* Provides a quick and easy place to log flights.
* Your data is yours to do with as you like. You will not lose access to your data even if the app ceases to exist (subject to you maintaining backups).
  * Data is stored internally in an open SQLite database.
  * Full backup and restore via text based JSON ensures you can always reinstall the app and get back to where you were.
  * All log data can be exported to CSV for easy transfer to spreadsheets or other systems.
  * Backup can be manual or scheduled by time or number of new records.
  * Backups can be saved to any available location, including Drive, Dropbox etc.
  * Inbuilt ability to write your logbook to PDF styled to match your paper logbook. Templates can be created to match most standard books.
* Provides dropdown lists for Gliders, Airfields and Clubs, with configurable defaults per airfield.
* Can be configured to record just flight time or launch, landing & flight time. Useful for compliance with some jurisdiction rules.
* Can be configured to save Landing place. Useful for compliance with some jurisdiction rules.
* Automatically selects the launch airfield and optionally the landing airfield (relies on airfield location being set).
* Select IGC file to automatically create a log record.
* Send the selected IGC file to WeGlide.
* Select an IGC file from an LXNav bluetooth enabled device (Nano, S100 etc)
* Time calculator to make filling in aircraft logbook easier. Provides last known AC logbook total + current day total. Last known total can be updated from the paper logbook to give the correct running total. For single user private gliders this will probably stay in sync with the glider logbook. For club and syndicate gliders you simply enter the last figure from the logbook and the app provides the new running total.
* Configurable filtering and sorting. Want to know how many hours you've flown a specific glider from a specific airfield? Need to report your past year's instructing hours?
* International language support. German available now, others can be easily created.
* Reasonably secure witness system. Witness simply takes a selfy and fills in their member/pilot/instructor number. The app encodes that and signs the record cryptographically. If the record is later edited it remains but is clearly flagged as edited since witnessed. From what I've seen this should be quite compliant in most jursidictions. Up to you to determine if it is in yours.

## What it doesn't do:
* Store, display or analyse IGC files. IGC files are parsed for flight launch and landing data then dropped or passed on to WeGlide. The files are retained for 7 days to allow for delays updating your logbook, but then automatically deleted. If you want to save them you need to copy them somewhere.
* In-flight logging. This is a logbook replacement, not a logger.
* Send your data anywhere off your device without you telling it to. No central database, no cloud storage, no third-party processing. WeGlide is the exception and only if you specifically tell it to.

## More info:
* I only use Android and usually non-stock versions.
* I generally don't use any of the big tech services where I don't have to.
* I'm not interested in dealing with Apple's walled garden, it goes against my leanings.
* There are airfields files for several countries available in the testdata folder. These are generated from flightbook.glidernet.org listed airfields, so by no means complete.
* 
 **So**
* How it functions on stock Android (eg Samsung or any other brand) is unknown to me. It should be fine - but who knows.
* Backup and restore should work seamlessly with Dropbox, Drive or other providers setup on your phone, but again - who knows.
* I've deliberately set this project up to be able to be ported to IOS, and I welcome someone doing it if they want. But I wont.

