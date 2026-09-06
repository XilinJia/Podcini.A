# Podcini.A

<img width="100" src="https://raw.githubusercontent.com/xilinjia/podcini/main/images/icon 256x256.png" align="left" style="margin-right:15px"/>

An open source extensible media instrument, attuned to Puccini ![Puccini](./images/Puccini.jpg), adorned with pasticcini ![pasticcini](./images/pasticcini.jpg) and aromatized with porcini ![porcini](./images/porcini.jpg), invites your harmonious heartbeats.

### Rendezvous chez:

[<img src="./images/external/getItGithub.png" alt="Get it on GitHub" height="50">](https://github.com/XilinJia/Podcini.A/releases/latest)
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="50">](https://f-droid.org/en/packages/ac.mdiq.Podcini.A/)
<!-- [<img src="./images/external/getItIzzyOnDroid.png" alt="IzzyOnDroid" height="50">](https://apt.izzysoft.de/fdroid/index/apk/ac.mdiq.podcini.A) -->

### A fork of [Podcini.X](<https://github.com/XilinJia/Podcini.X>) as of May 29 2026, this project inherits all functionalities of Podcini.X.

### A major milestone is this project can handle sources provided by external apps.
Available such apps are: 
[UT.urn](<https://github.com/XilinJia/UT.urn>), 
[PeerPop](<https://github.com/XilinJia/PeerPop>) and
[CloudSound](<https://github.com/XilinJia/CloudSound>)

## Notable features

1. Not only handling podcasts, local media, but also any remote media served by external source providers.
2. Features multiple, natural and circular play queues associable with any feed.
3. Features volume hierarchies each of which can contain sub-volumes and feeds.
4. Presents synthetic feeds and allows media to be separately shelved.
5. Remote media (unsubscribed) can be reserved or played.
6. Allows online search of remote media provided by external sources.
7. Enables setting tags, todos, notes/comments, 5-level rating, and 12-level play state on every media.
8. Boasts sophisticated sorting, filtering and searching on media and feeds.
9. Supports sleep and auto-play timers.
10. Presents 2 independent algorithms for auto-download or auto-enqueue specifiable on every feed.
11. Supports spaced repetition of repeat media: auto-download or auto-enqueue them at preset intervals specified in the feed.
12. Caches streamed audio for seamless local rewind and replay.
13. is capable of playing 2 media simultaneously (one in each ear with earphones) with independent controls.
14. Features audio clips recording and position marking on any media for better review.
15. Allows linking/relating multiple media for better grouping.
16. Is capable of preserving important media when a feed is unsubscribed.
17. Spotlights sending/receiving feeds and subscriptions catalogue across devices without a server.
18. Offers Readability and Text-to-Speech for RSS contents.
19. Supports auto-backups, customized media folder and importing DB from other apps

### Note:

#### Since 12.9.2, each of the Free and Play builds in the releases here is accompanied with a "Legacy" release. 
It's the same app as the standard build, the difference is that in a Legacy apk dependency libs are compressed therefore reducing the size of the apk.
The downsize is install size of the app will be somewhat larger (keeping both the compressed and uncompressed libs). 
All variants in a release are inter-changeable, so you can download/install any one and re-try another one/
#### For Podcini to show up on car's HUD with Android Auto, please read AnroidAuto.md for instructions.
#### If you need to cast to an external speaker or screen, you should install the "play" apk, not the "free" apk, that's about the difference between the two.

Podcini.A requests for permission for unrestricted background activities for uninterrupted background play of a playlist.  For more see [this issue](https://github.com/XilinJia/Podcini.X/issues/88)

If you intend to sync through a server, NextCloud server has been tested, but note only very limited properties (as defined by that standard) are included.

### [Warning: Your Android device will become a locked-down platform soon](https://keepandroidopen.org/)

## Usage and notable features description 

To use external server apps, you need to first install the apps. Then in Podcini.A -> Settings->Network and Storage, enable "Use external apps".
 
<details> <summary>Click to expand</summary>

### Quick start

* On a fresh install of Podcini, do any of the following to get started enjoying the power of Podcini:
  * Open the drawer by right-swipe from the left edge of the phone
  * Tap "Add feeds", in the new view, enter any key words to search for desired feeds, see "Online feed" section below
  * Or, from the drawer -> Settings -> Import/Export, tap either "Import AntennaPod DB" or "Import Podcast Addict DB" to import the DB you have exported from the other apps.
  * Or, from the drawer -> Settings -> Import/Export, tap OPML import to import your opml file containing a set of feed
  * Or, open any app on the phone, if source is supported by an external source provider, select a channel/playlist or a single media, and share it to Podcini.

Note, if you already have subscriptions in Podcini, importing the OPML file or the DB from the other apps will not erase your existing data in Podcini, except if the imported DB contains common feeds you have in Podcini, the imported data will overwrite on those in the existing subscriptions in Podcini.
  
### Feed

* A feed can be subscribed online or loaded from a local directory with media files
* Every feed can be associated with a queue allowing downloaded media to be added to the queue
* In addition to subscribed feeds, synthetic feeds can be created and work as subscribed feeds but with extra features:
  * media can be copied/moved to any synthetic feed
  * media from online feeds can be shelved into any synthetic feeds without having to subscribe to the online feed
  * media shared from external sources are added in synthetic feed
* FeedDetails screen has two views: FeedInfo and FeedEpisodes, which can be toggled by tapping on the cover image
* FeedDetails screen contains FeedInfo and FeedEpisodes views
* FeedInfo view offers a link for direct search of feeds related to author
* In FeedInfo view, one can enter personal comments/notes under "My opinion" for the feed
* A rating of Trash, Bad, OK, Good, Super can be set on any feed
* on action bar of FeedEpisodes view there is a direct access to the associated Queue, if any
* A rating of Trash, Bad, OK, Good, Super can be set on any feed
* on action bar of FeedEpisodes view there is a direct access to Queue
* Long-press filter button in FeedEpisodes view enables/disables filters without changing filter settings
* Podcast's settings can be accessed in FeedInfo and FeedEpisodes views
* "Prefer streaming over download" is now on setting of individual feed
* Added audio type setting (Speech, Music, Movie) for improved audio processing
* added setting to play audio only for video feeds,
  * an added benefit for setting it enables external media to only stream audio content, saving bandwidth.
  * this differs from switching to "Audio only" on each media, in which case, video is also streamed
* RSS feeds with no playable media can be subscribed and read/listened (via TTS)
* there are two ways to access TTS: from the action bar of EpisodeHome view, and on the list of FeedEspiosdes view
  * the former plays the TTS instantly on the text available, and regardless of whether the media as playable media or not, and the app can't control the playing except for play/pause
  * the latter, only available when the media is plain RSS, does not play anything, instead, it constructs an audio file (like download) to be played as a normal media and the speed/rewind/forward can be controlled in Podcini


### Volume

* A volume is a container that can contain various number of feeds and various number of sub-volumes
* It can be used to organize similar feeds
* It can be loaded from a local directory tree when importing local feeds
  
### Media

* New share notes menu option on various media views
* there is a new rating system for every episode: Trash, Bad, OK, Good, Super
* there is a new play state system: Unspecified, Building, New, Unplayed, Later, Soon, Queue, Progress, Again, Forever, Skipped, Played, Passed, Ignored
  	* among which Unplayed, Later, Soon, Queue, Again, Forever, Skipped, Played, Passed, Ignored are settable by the user
	* when an episode is started to play, its state is set to Progress
	* when an episode is manually set to Queue, it's added to the queue according to the associated queue setting of the feed
	* when an episode is added to a queue, its state is set to Queue, when it's removed from a queue, the state (if lower than Skipped) is set to Skipped
	* when an episode is set to Again or Later, a due time can be specified
* in EpisodeInfo view, one can enter personal items:
  * comments/notes under "My opinion" for the episode
  * Todo with note and due time with timer
  * tags
* New media home view with two display modes: webpage or reader
* In media, in addition to "description" there is a new "transcript" field to save text (if any) fetched from the media's website

### Feed list

* Library page by default has a list layout and can be opted for a grid layout for the feeds subscribed
* An all new sorting dialog and mechanism for Library based on title, date, time and count combinable with other criteria
* An all new way of filtering for both feeds and media with expanded criteria.
  * some multi-factor criteria options are hidden by default, tap on the criteria to show the options.
* in feeds list, click on cover image of a feed opens the FeedInfo/FeedEpisodes
* New and efficient ways of click and long-click operations on both podcast and episode lists:
  * click on title area opens the podcast/episode
  * long-press on title area automatically enters in selection mode
  * options to select all above or below are shown action bar together with Select All
  * operation options are prompted for the selected (single or multiple)
  * in episodes lists, click on an episode image brings up the FeedInfo view
* Downward swipe triggered feeds update
  * in Library view, all feeds are updated
  * in FeedEpisodes view, only the single feed is updated
* Local search for feeds or episodes can be separately specified on title, author (feed only), description (including transcript in episodes), and comment (My opinion)

### Episode list

* Episode lists appears in various screens: Queues (including bins), Facets, FeedEpisodes, OnlineFeed, etc.
* On most such lists, an episode can be played/streamed by pressing the action button on the episode
* when playing/streaming an episode from screen other than Queues, a sub-list of episodes are added to the virtual queue for better tracking
* The action buttons are normally formed automatically, but they allow to be customized in a feed settings.
* For play or stream, three actions are supported: normal (play next when one is finished), One (only play one episode), or Repeat (repeating the one episode)
* Long-press on the action button on any episode list brings up more options
* An all new sorting dialog and mechanism for Subscriptions based on title, date, time and count combinable with other criteria
* An all new way of filtering for both podcasts and episodes with expanded criteria.
  * some multi-factor criteria options are hidden by default, tap on the criteria to show the options.
* FeedEpisodes has the option to show larger image on the list by changing the "Use wide layout" setting of the feed
* media view provides easy access to various filters:
  * AllEpisodes, History and Download
  * New, Planned (for Soon and Later), Repeats (for Again and Forever), Liked (for Good and Super)
* media list is shown in views of media, FeedEpisodes, and OnlineEpisodes
* Media list is shown in views of Facets, FeedEpisodes, and OnlineEpisodes
* New and efficient ways of click and long-click operations on both feed and media lists:
  * click on title area opens the feed/media
  * long-press on title area automatically enters in selection mode
  * options to select all above or below are shown action bar together with Select All
  * operation options are prompted for the selected (single or multiple)
  * in media lists, click on an media image brings up the FeedInfo view
* media lists supports swipe actions
  * Left and right swipe actions on lists now have telltales and can be configured on the spot
  * Swipe actions are brought to perform anything on the multi-select menu, and there is a Combo swipe action
  * Playing an episode at a specified future time can be set with a swipe action
* Downward swipe triggered feeds update
  * in Subscriptions view, all feeds are updated
  * in FeedEpisodes view, only the single feed is updated
* in media list view, if media has no media, TTS button is shown for fetching transcript (if not exist) and then generating audio file from the transcript. TTS audio files are playable in the same way as local media (with speed setting, pause and rewind/forward)
* Long-press on the action button on the right of any media list brings up more options
* Deleting and updating feeds are performed promptly
* Local search for feeds or media can be separately specified on title, author (feed only), description (including transcript in media), and comment (My opinion)

### Queues

* Multiple queues can be used: 5 queues are provided by default, user can rename or add up to 10 queues
  * on app startup, the most recently updated queue is set to active queue
  * any media can be easily added/moved to the active or any designated queues
  * any queue can be associated with any feed for customized playing experience
* Every queue is circular: if the final item in queue finished, the first item in queue (if exists) will get played
* Every queue has a bin (accessible from the top bar of Queues view) containing past media removed from the queue, useful for further review and handling
* Feed associated queue can be set to None, in which case:
  * the media in the feed are not automatically added to any queue, instead FeedEpisodes view forms a natural queue on their own
  * when playing an media in FeedEpisodes view, the next media to play is determined in such a way:
    * if the currently playing media had been (manually) added to the active queue, then it's the next in the queue
    * else if "prefer streaming" is set, it's the next unplayed (or Again and Forever) media in the natural queue based on the current filter and sort order
    * else it's the next downloaded unplayed (or Again and Forever) media
* There is a button on the top bar of the Queues view to show associated feeds 
* Otherwise, media played from a list other than the queue is a one-off play, unless the media is on the active queue, in which case, the next media in the queue will be played

### Player

* More convenient player control displayed on all pages
* Player UI (button row) is horizontally swipable: to the left hides the player to the drawer (tap the teaser image at bottom of drawer to restore), to the right brings up the sleep timer dialog
* the cover image in Player UI
  * Tap opens the detailed info for the episode plus user set items
  * Long-press opens the Feed details
* Playback speed setting has been straightened up, three speed can be set separately or combined: current audio, podcast, and global
* There are two mechanisms in updating playback progress (configurable in Settings): every 5 seconds or adaptively at the interval of 2 percent of the media duration
* Volume adaptation control is added to player detailed view to set for current media and it takes precedence over that in feed settings
* The speedometer shows the current play speed, 
  * on tap, shows a dialog where various attributes can be set 
  on long-press, shows volume adaptation settings
* The Record button, when tapped, starts/ends recording, when long-pressed, records a timestamp marker, both of which can be accessed from Play detailed view
* Added preference "Fast Forward Speed" and "Fast Skip Speed" under "Playback" in settings with default value of 0.0, dialog allows setting a number between 0.0 and 10.0
* The Rewind button rewinds on tap by the number of seconds customizable, on long-press restarts the current media
* The Forward button forwards on tap by the number of seconds customizable
  * on long-press, if the user customize "Fast Skip Speed" to a value greater than 0.1
    * plays at the set speed,
    * long-press again restores the normal play speed
  * The Skip" button on the player
  * long-press moves to the next media
  * by default, single tap does nothing
  * if the user customize "Fast Forward Speed" to a value greater than 0.1, it behaves in the following way:
    * single tap during play, the set speed is used to play the current audio
    * single tap again, the original play speed resumes
    * single tap not during play has no effect
* Added preference "Fallback Speed" under "Playback" in settings with default value of 0.0, dialog allows setting a float number (capped between 0.0 and 1.5)
* if the user customizes "Fallback speed" to a value greater than 0.1, long-press the Play button during play enters the fallback mode and plays at the set fallback speed, single tap exits the fallback mode
* streamed media somewhat equivalent to downloaded media
  * there is a streaming cache, so mostly, Rewind/Forward on streaming simply operates from the cache
  * enabled media description on player detailed view
  * enabled intro- and end- skipping
  * mark as played when finished
  * streamed media is added to queue and is resumed after restart
* There are three modes for playing video: fullscreen, window and audio-only, they can be switched seamlessly in video player
* Video player automatically switch to audio when app invisible or when switching to other views in the app.
* when video mode is set to audio only, click on image on audio player on a video media brings up the normal player detailed view
* media played to 95% of the full duration is considered completely played

### Online feed

* Upon any online search (by Add feeds), there appear a list of online feeds related to searched key words
  * a webpage address is accepted as a search term
* Long-press on a feed in online feed list prompts to subscribe it straight out.
* Press on a feed opens Online feed view for info or media of the feed and opting to subscribe the feed
* Online feed info display is handled in similar ways as any local feed, and offers options to subscribe or view media
* Online feed media can be freely played (streamed) without a subscription
* Online feed media can be selectively reserved into synthetic feeds without subscribing to the feed

### External sources

* Channels (if applicable) can be searched in podcast search view, can also be shared from other apps to Podcini
* Channels can be subscribed as normal feeds
* When subscribing to a channel, tabs can be chosen to form separate feeds
* Playlists and podcasts Music can be shared to Podcini, and then can be subscribed in similar fashion as the channels
* Subscribed channels, playlists/podcasts can be updated as with normal feeds
* Single media can also be shared from other apps, and added in a chosen synthetic feed
* All the media can be played (only streamed) with video in fullscreen or in window modes or in audio only mode in the background
* Audio and video quality settings in Feed Preferences (if applicable): Global, Low, Medium, High
	* these settings take precedence over global situations
	* when Global is set, video is at lowest quality, and audio is at highest quality (except when prefLowQualityMedia is set for metered networks)
* If a subscription is set for "audio only", then only audio stream is fetched at play time for every media in the subscription

### Instant (or Wifi) sync

* Ability to sync between devices on the same wifi network without a server (experimental feature)
* It syncs the play states (position and played) of media that exist in both devices (ensure to refresh first) and that have been played (completed or not)
* So far, every sync is a full sync, no sync for subscriptions and media files

### Automation

* Auto refresh (feed updates) can be set with an interval in hours. Start time is "now" unless it's separately set
  * Note these timing are not guaranteed to be exact. Android has interests in controlling them.
* Auto download algorithm is based on settings in individual feed.
  * When auto download is enabled in the global Settings, by default, all undownloaded media in queues are candidates for download
    * whether or which queues are included in auto-download can be configures in Settings
  * Auto downloads run after feed refresh, scheduled or manual
  * Auto-downloading of media in any feed need to be separately enabled in the feed settings.
  * Each feed has its own limit (Media cache) for number of media downloaded, this limit rules in combination of the global limit for the app.
  * Each feed can have its own download policy 
    * Only new: only new items at refresh time are download candidates.
      * without Replace, if old downloaded items (fulfilling the allowed cache) have not been played, new items will not be downloaded.
      * with Replace, new items will replace old downloaded items
    * Newest: the newest items (not necessarily new) are downloaded
    * Oldest: the oldest items are downloaded
    * Marked as Soon: only those marked as Soon are downloaded (in order of pub date descending)
    * Current filter and sort: the items to be downloaded depend on the current filtering and sorting criteria set in FeedDetailed screen
      * the current filtering and sorting criteria are copied so, once set, future changes won't affect auto-download behavior
    * Those marked as Soon can be separately enabled, and once enabled, takes precedence over normal policies
  * After auto download run, media with New status in the feed is changed to Unplayed.
  * In auto download feed setting, inclusive and exclusive filters can be set (if needed) 
    * there are now separate dialogs for inclusive and exclusive filters where filter tokens can be specified independently
    * on exclusive dialog, there are optional check boxes "Exclude media shorter than" and "Mark excluded media played"
* Auto enqueue algorithm is based on settings in individual feed.
  * Auto enqueue run after feed refresh, scheduled or manual
  * Auto-enqueuing of episodes in any feed need to be separately enabled in the feed settings.
  * Each feed has its own limit (Episode cache) for number of episodes enqueued, this limit rules in combination of the global limit for the app.
  * Each feed can have its own enqueue policy 
    * Only new: only new items at refresh time are enqueue candidates.
      * without Replace, if old enqueued items (fulfilling the allowed cache) have not been played, new items will not be enqueued.
      * with Replace, new items will replace old enqueued items
    * Newest: the newest items (not necessarily new) are enqueued
    * Oldest: the oldest items are enqueued
    * Current filter and sort: the items to be enqueued depend on the current filtering and sorting criteria set in FeedDetails screen
      * the current filtering and sorting criteria are copied so, once set, future changes won't affect auto-enqueue behavior
  * Those marked as Soon can be separately enabled, and once enabled, takes precedence over normal policies
 * After auto-enqueue run, episodes with New status in the feed is changed to Unplayed.
  * In auto-enqueue feed setting, inclusive and exclusive filters can be set (if needed) 
    * there are now separate dialogs for inclusive and exclusive filters where filter tokens can be specified independently
    * on exclusive dialog, there are optional check boxes "Exclude episodes shorter than" and "Mark excluded episodes played"
* Sleep timer has a new option of "To the end of media"

### Statistics

* Statistics compiles the media that's been played during a specified period and for today
* There are 2 numbers regarding played time: duration and time spent
  * time spent is simply time spent playing a media, so play speed, rewind and forward can play a role
  * Duration shows differently under 2 settings: "including marked as play" or not
  * In the former, it's the full duration of a media that's been ever started playing played
  * In the latter case, it's the max position reached in a media

### Security and reliability

* Disabled `usesCleartextTraffic`, so that all content transmission is more private and secure
* there are three sets of loggings: media downloaded, contents shared to Podcini, and contents removed from Podcini (either feeds or individual media in synthetic feeds) 
* in Import/Export settings, there is a new Combo Import/Export
	* it handles Preferences, Database, and Media files combined or selectively
	* all are saved to "Podcini-Backups-(date)" directory under the directory you pick
	* on import, Media files have to be done after the DB is imported (the option is disabled when importing DB is selected)
	* individual import/export functions for Preferences, Database, and Media files are removed
	* if in case one wants to import previously exported Preferences, Database, or Media files, 
		* manually create a directory named "Podcini-Backups"
		* copy the previous .realm file into the above directory
		* copy the previous directories "Podcini-Prefs" and/or "Podcini-MediaFiles" into the above directory
		* no need to copy all three, only the ones you need
		* then do the combo import
* There is an option to turn on auto backup in Settings->Import/Export
  * if turned on, one needs to specify interval (in hours), a folder, and number of copies to keep
  * then Preferences and DB are backed up in sub-folder named "Podcini-AudoBackups-(date)"
  * backup time is on the next resume of Podcini after interval hours from last backup time
  * to restore, use Combo restore 
* Folder for downloaded media can be customized
  	* the use of customized folder can be changed or reset
	* folder in SD card should also work (someone try it out)
	* upon change, downloaded media files are moved from the previous folder to the new folder
	* export and reconcile should also work with customized folder
* Play history/progress can be separately exported/imported as Json files (once needed when migrating from Podcini 5 with a different DB. now it doesn't seem to provide much benefit if one export/import the DB).
* Reconcile feature (accessed from Downloads in media view) is added to ensure downloaded media files are in sync with specs in DB
* Feeds can be selectively exported from Library view
* There is a setting to disable/enable auto backup of OPML files to Google
* Upon re-install of Podcini, the OPML file previously backed up to Google is not imported automatically but based on user confirmation.

For more details of the changes, see the [Changelog](changelog.md)

</details>

## Screenshots

### Settings
<img src="./images/Drawer.jpg" width="238" /> <img src="./images/Setting-UI.jpg" width="238" /> 

<img src="./images/Settings-Import-Export.jpg" width="238" /> <img src="./images/Settings-Network-Storage.jpg" width="238" /> 
<img src="./images/Settings-Playback.jpg" width="238" /> 

### Library
<img src="./images/Library-volumes-menu.jpg" width="238" /> <img src="./images/Library-sub-volumes.jpg" width="238" /> <img src="./images/Library-Icons.jpg" width="238" />

<img src="./images/Library-filter.jpg" width="238" /> <img src="./images/Library-sort.jpg" width="238" /> <img src="./images/Library-multi-select-menu.jpg" width="238" />

### Feed
<img src="./images/Feed-filter.jpg" width="238" /> <img src="./images/Feed-filter-ratings.jpg" width="238" /> <img src="./images/Feed-list-player.jpg" width="238" />

<img src="./images/Feed-menu.jpg" width="238" /> <img src="./images/Feed-Syndicate.jpg" width="238" /> 

### Feed settings
<img src="./images/Feed-settings.jpg" width="238" /> <img src="./images/Feed-settings-policy.jpg" width="238" />

### Media lists, queues, and easy access
<img src="./images/Episodes-sort.jpg" width="238" /> <img src="./images/Queues-menu.jpg" width="238" /> <img src="./images/Facets-menu.jpg" width="238" />

<img src="./images/Stream-options-menu.jpg" width="238" /> <img src="./images/Episodes-multi-select-menu.jpg" width="238" />

### Media and player details
<img src="./images/Episode.jpg" width="238" /> <img src="./images/Player-details.jpg" width="238" /> 

### Double players
<img src="./images/Double-players.jpg" width="238" /> 

### Youtube share and media
<img src="./images/Youtube_share.jpg" width="238" />  <img src="./images/Youtube_shared.jpg" width="238" /> 


### Usage customization
<img src="./images/Speed-popup.jpg" width="238" /> <img src="./images/swipe-setting.jpg" width="238" /> <img src="./images/Swipe-settings-menu.jpg" width="238" /> 

### Get feeds online
<img src="./images/Add-feeds.jpg" width="238" /> <img src="./images/9_online_feed_info.jpg" width="238" /> <img src="./images/91_online_episodes.jpg" width="238" />

### Android Auto
<img src="./images/92_Auto_list.png" width="238" /> <img src="./images/92_Auto_player.png" width="238" />

## Links

* [Changelog](changelog.md)
* [Privacy Policy](PrivacyPolicy.md)
* [Contributors](CONTRIBUTORS.md)
* [Contributing](CONTRIBUTING.md)
<!-- * [Translation (Transifex)](https://app.transifex.com/xilinjia/podcini/dashboard/) -->
* [Translation (Crowdin)](https://crowdin.com/project/podcini)

## License

Podcini, same as the project it was forked from, is licensed under the GNU General Public License (GPL-3.0).
You can find the license text in the LICENSE file.

## Copyright

New files and contents in the project are copyrighted in 2024 by Xilin Jia and related contributors.

Original contents from the forked projects maintain copyrights of the original developers.

## Licenses and permissions

[Licenses and permissions](Licenses_and_permissions.md)
