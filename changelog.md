# 12.8.2

* fixed error playing downloaded file
* player buffer updates on a flow every 5 seconds
* show updated duration (when invalid) in episodes list and playerUI
* in PlayerUI
	* skip forward text is shown on top of Skip button (consistent with other single clicks)
	* button positions and buffer indicator are adjusted
* in AVPlayer screen, changing speed in video player is reflected in PlayerUI
* likely fixed possible PlayUI flickers on start
* in OnlineFeed, ensure "Likely removed" list has not duplicates
* cleared dependency on compose runtime in non-UI routines
* re-worked curMedia monitoring
* improved list info calculation
* some adjustment in FeedSettings
* some events removed and some code refactoring, 

# 12.8.1

* in Remote tab of Search screen, fixed issue with multiple searchers
* fixed position saver interval not reset on new media
* ensure to cancel prior schedule when changing a timer or due time of a Todo
* tuned toasts when binding external client
* amended use external apps text in Settings
* reordered FeedSettings
* theme changing is live, no restart needed
* wifi sync is turned off, antique version
* some code cleaning and refactoring

# 12.8.0

* in FeedSettings screen, enabled Audo-enqueue/download policy setting when setting for multiple feeds
* added a more versatile episodes sorting routine 
* enabled feed based sorting in most modes of Facets screen
* changed "Views per day" to "Trending" and enabled the sorting (if supported) in FeedDetails and Facets screens
* amended Todo editing
	* due time setting is in one field, needs to be confirmed
	* episode will play at due time only when Notify is checked
* in Timer editing, due time setting is in one field
* in Overview of Statistics, fixed today's date
* appAttribs and appPrefs are made into flows
* in Remote tab of Search screen
	* search needs to be manually initiated
	* added Apple searcher for episodes, search strictly on title
* playback streaming migrated to supporting http/3/2/1.1 auto switch, better for high-latency networks
	* only non-authentication based proxy is supported (someone please verify)
* removed redundant initialization in EpisodesDownloadWorker, FeedUpdateWorker and MediaButtonReceiver
* some code refactoring

# 12.7.1

* tuned app initialization
* timers are only allowed to be set to the future
* in Timers view of Facets screen, fixed crash when auto clearing old timers
* added Episode action "Add Todo" to restore adding todos to episodes
* amended Todos display and editing
* in OnlineFeed screen, fixed error on Subscribe and exit
* some code refactoring

# 12.7.0

* corrected position saver interval setting based on 12.6.3
* pause player and cancel position saver after multi-rounds of same position
* fixed possible infinite loop of open/close cache data source
* fixed issues with playing with a timer
* in Toast popup, added Close icon to clear currently shown
* amended OnlineFeedItem
* in TopChart screen, 
	* ensure first search results are based on the selected country
	* improved country selection popup
* in FindFeeds screen
	* added result counter near the top
	* search results are cached
	* added "Apple deep searcher" to scrap websites for hidden feedUrl's, slower, excluded from Combined searcher

# 12.6.3

* ensure external client is available in ShareReceiverActivity
* in media player
	* disabled VBR indexing, likely the culprit for startup delays on some media
	* if useAdaptiveProgressUpdate is set, position saver interval is 2% of duration adjusted with speed, and reset on speed change
* in PlayerUI, buffer value is reset on start of new media
	
# 12.6.2

* ensured when adding comment in an media, no new timestamp is added within 30 minutes from last edit
* ensured deletion log id of an episode/feed is set to its id
* in Deletions view of Logs screen, logs are sorted by deletion date descending
* in FeedDetails screen, show past deletion logs of the feed if any

# 12.6.1

* likely fixed external clients possibly not ready when updating feeds
* in feed Updater, ensure the correct feed id is added in the log for external feeds
* in Logs screen, ensured again past superseded Shares and Downloads logs are not shown
* in Info view of FeedDetails screen, removed languages, added parent volume and associated queue
* when adding comment in an media, a new time stamp is added only if it's more than 30 minutes from the previous time stamp
* upped Compose and some other dependencies

# 12.6.0

* Feed auto download/enqueue specs are moved to a separate DB model, DB migration is performed on app update
* rearranged FeedSettings, and added "Enable second algorithm" for auto-download/enqueue
* Second algorithm is enabled in auto-download/enqueue routine
* improved handling of feed flow in FeedDetails screen
* in the popup from speedometer on the Player UI, added helper text
* in Settings->Playback, removed those redundant settings that can be more conveniently set from the speedometer on the Player UI

# 12.5.12

* fixed filter again in Facets screen,
* when manually setting episodes to Skipped, Passed, Ignroed, they are auto removed from queues
* in MediaPlayer, when checking duplicates, not set curEpisode to Ignored when duplicates were set to ignored
* integrated simplified Chinese translation (someone did for Podcini.X - thank you, and please identify yourself)

# 12.5.11

* in Facets screen, fixed filter and sort, and disabled the buttons in modes not relevant
* Feed title sorting for episodes is only available in Queues screen
* added "Feed score" and "Feed score count" sortings for episodes in Queues screen
* disabled sorts of View counts, views per day, Like count in FeedDetails and OnlineFeed screens for feed not supporting them
* in FeedSettings, volumes are sorted by name in parent volume popup
* in History mode of FeedDetails and Bin mode of Queues, Play/Stream buttons are set to Single
* in FeedUpdate, avoid changing feed title that can remove tab appendix on a YT feed
* minor dependencies update

# 12.5.10

* in MediaPlayer, when checking duplicates, episodes with same title are always added as Related regardless of duration
* amended/fixed clip recording, added toast on media from external sources not supported
* in OnlineFeed, ensure to toast error when given url has not valid page
* modernized the parse date routine
* in FindFeed screen, fixed setting of "Search all sources"

# 12.5.9

* in OnlineFeed screen
	* if feed exists, "Limit episodes" row is not shown, number of episodes in existing feed is shown
	* if the existing feed has a different url, "Update url" button is shown, which updates the feed's url and opens it
* in Library, reset language filter when feeds are added/removed
* in MediaPlayer, when checking duplicates, duration is checked to be close
* in EpisodeInfo, show last played date if set
* ensured when receiving feeds from other device, feeds are put to the current volume
* some code refactoring
* updated media3
	
# 12.5.8

* fixed issue of external audio media bypassing player cache during playback
* in Logs screen
	* past superseded Shares logs are not shown
	* in Downloads details popup
		* Open button is present also for failure download
		* Redo is changed to Retry, and is present for failure download
* texts in OnlineFeed screen are selectable

# 12.5.7

* further amended Logs screen
	* replaced mode title with icon
	* added switch in Session, Downloads and Shares modes to show success/error logs only
	* in Downloads mode
		* past superseded items are not shown
		* Redo action is moved to the dialog
	* reformatted details dialogs
	* Clear logs is moved to the menu
	* EpisodeInfo opened from Logs screen does not have a Close icon
* some code cleaning, and removed most ordinal references in enum classes

# 12.5.6

* in Shares mode of Logs screen
	* click on a Success log with missing feed/episode turns it into Missing status
	* click on a Error/Missing log opens the share dialog
	* fixed crash on log handled by an external app
* in Downloads mode of Logs screen, details of a Success log are shown on popup
* in FeedSettings, fixed edit url not persisted
* not toasting "new episode duration less than 1 second"

# 12.5.5

* in StreamChanger popup, added Protocols for video streams, and only selecting bitrate or resolution activates Confirm
* tuned player buffer sizing for faster starts and little hiccups
* in Library:
	* amended feed origin filters and fixed crash
	* reset language filter when feeds are added/removed
	* when receiving feeds from other device, feeds are put to the current volume
* made toast show up in front of others
* in Search screen, ensure to add all query strings to history 
* fixed reminder of past due media showing too often (interval should be at least 24 hours)
* disabled Download option in action button's alternative menu for media from external apps (not supported yet)
* in Settings->Network and Storage, setting refresh interval to 0 no longer pops up confirm action
* tuned some initialization procedure
* some code refactoring

# 12.5.4

* toasts defer to confirm actions, and are stripped off tags
* error toasts duration back to 3 seconds
* added locked/unlocked on toast popup, when locked, toasts stay for up to 1 minutes
* fixed StreamChanger for PeerTube media
* fixed invalid video sources in PeerTube together with external app PeerPop (update needed)

# 12.5.3

* toast messages are queued (no longer overriding) and shown with max of 3 at a time
* increased error toasts duration to 5 seconds
* confirm actions are also queued (no longer overriding) and shown one at a time
* in media swipe actions, added "Set due date" action for Again, Forever and Later media
* in media lists, Forever media is also shown with a due date

# 12.5.2

* in Facets screen, added mode Due for all Again and Forever media past due
* in Auto-download/enqueue algorithms, due Again and Forever media are no longer added to queue (Later media remain unchanged)
* in MainActivity, a reminder of past due media is shown every day, which opens the Due mode in Facets screen

# 12.5.1

* in PlayerDetailed, ensure force-video and audio-only icons show for proper media in synthetic feed
* in MediaPlayer, not unset force-video in pause
* in PlayerUI
	* increased distance threshold for the horizontal swipe
	* removed sample rate info
* EpisodeInfo opened from Queues, Search, and Facets screens shows feed icon in topbar
* in Logs screen, changed "Subscriptions" to "Deletions", and the icon

# 12.5.0

* fixed force video
* improved handling of switch between force video and audio only 
	* force video persists with the media, audio only (in a prefer-video feed) applies when current media is not changed
* in PlayerDetailed
	* replaced external media panel with "Change stream" in the menu, shown only on applicable media
	* added StreamChanger popup, where if applicable, one can change locale, codec and biterate for audio, and video codec and resolution for video
* some code refactoring

# 12.4.15

* in MediaPlayer,
	* ensure to reset preferred locale, codec and bitrate on new episode
	* when eligible audio streams list is empty, toast about languages availability and preference
* if a media set to "Audio only" resorts to muxed video, video is shown in PlayerDetailed
* amended Preferred languages settings and added note in Settings->Playback and Feed settings
* some dependencies update

# 12.4.14

* added description in SubscriptionLog
* in OnlineFeed
	* ensure to show all prior cancellation logs (if any) possibly related to the feed 
	* if feed was previously unsubscribed but preserved (frozen), shows the preserved
* Note, to unfreeze a feed
	* first set the feed to a normal parent volume (or none) in the settings, then enable update
	* do "Refresh complete feed" from the menu in FeedDetails screen
* some code refactoring

# 12.4.13

* avoid reset playback of current media when feed changed is unrelated
* "Use muxed video" is changed to "Prefer muxed video"
* fixed OnlineFeed might get open twice when sharing feed from other apps
* in OnlineFeed
	* further improved handling existing feed from external app
	* ensure to show prior cancellation logs (if any) of the feed from external apps 

# 12.4.12

* amended VideoMode dialog
	* confirm is needed to set the option
	* in VideoMode setting of a feed, added "Use muxed video" option if use video, to use the muxed video stream
		* even if it's not set, if separate audio/video is not available, muxed video stream is the last resort
		* the option is currently only applicable to sources from UT.urn (others don't have separate streams)
* force reset playback of current media when feed VideoMode, audio quality, or video quality changes
* in PlayerUI, resolution is shown when playing video
* not throwing exception when client provides empty eligible audio stream list
* likely fixed clients mal-functioning after toggling "Use external apps"
* corrected color of error toasts
* some code refactoring and cleaning

# 12.4.11

* in Search screen, catch exception when search string contains special characters the DB doesn't accept
* when PlayerUI is swiped away, note "Player UI is in the drawer" at bottom of main screen
* on "Use external apps" in Settings->Network and Storage
	* amended summary text
	* on toggle, ensure disconnecting services and force reset playback of current media
* toasting of audio offload switching is no longer tied to a media
* some toast tuning and code refactoring
* AGP upped to 9.3.1, compileSDK upped to 37.1

# 12.4.10

* in PlayerDetailed, show external media panel even when playing
* in OnlineFeed, improved handling existing feed from external app

# 12.4.9

* in PlayerDetailed
	* added media url
	* external media panel is shown only when not playing 
	* when changing audio stream in the panel, clears the media from cache first
* both url in EpisodeInfo and PlayerDetailed are long-clickable for sharing
* in PlayerUI, fast rewind icon is changed to skipback and rewind icon (long-click rewinds to beginning)
* adjusted Feed scoring algorithm a bit: partially played media does not penalize if the media is given a higher rating than OK
* toasting of audio offload switching is no longer tied to a media
* some code cleaning and refactoring

# 12.4.8

* in SearchScreen, Remote tab is not shown if external apps are not used/connected
* in Remote tab of SearchScreen, ensure to clear cache and re-search when searchers are changed
* improved getting client by media, fixed PeerTube media not playing in Remote tab of SearchScreen
* added SearchScreen access in the drawer
* "Use external app" is now "Use external apps"
* some code refactoring

# 12.4.7

* in Remote tab in SearchScreen, on popup of "Reserve all"
	* the search string plus searcher names are suggested as the feed name
	* added option of feed type and hasVideo
	* fixed crash on confirm
* in FeedSettings
	* added setting (with note) for feed type if it is synthetic, which activates proper settings for audio/video prefs
	* enabled audio/video qualities settings for synthetic feed
* added note in creating synthetic feed
* top searchbars are set for single line
* static circular progress indicators are set to animate
* some code refactoring

# 12.4.6

* in Remote tab in SearchScreen
	* added searching indicator on infobar
	* in menu, added "Reserve all" (shown only when search is complete) to save all the media to a synthetic feed
* when playing a remote media, it's automatically saved in synthetic feed "Remote history"
	* applicable to media in Remote tab in SearchScreen and Episodes mode of OnlineFeed screen
* untoast "set player buffer"

# 12.4.5

* likely fixed possible run time error when changing player buffer

# 12.4.4

* improved player buffer and made it dynamically set based on play speed
* one media list in Remote tab in SearchScreen is cached, re-search is not needed when come back

# 12.4.3

* ensure player position functions when playing from Remote tab of SearchScreen or Episodes mode of OnlineFeed screen
* added global qualities settings for audio and video (if any installed external app supports) in Settings->Playback

# 12.4.2

* in Remote tab of SearchScreen, disabled swipe actions (not applicable)
* Amended create/rename synthetic feed
	* name entry field is limited to one line
	* created feed is set to the current volume
* limit name entry field to one line in create/edit volume
* fixed possible crash when shelve episodes to a synthetic feed
* added note on selecting proper feed in add-to-synthetic-feed dialog

# 12.4.1

* PodciniLib upped to 1.1.2, external apps (if used) need to be updated for compatibility
* fixed video not showing from Remote tab of SearchScreen
* when tap on Pause in PlayerUI, not open PlayerDetailed when playing video
* gradle upped to 9.6.1, AGP upped to 9.3.0

# 12.4.0

* adopted improved API in PodciniLib 1.1.1
	* external apps (if used) need to be updated for compatibility
	* search for playlists and individual media is enabled
* added SoundCloud support via external app CloudSound
* in SearchScreen
	* changed advanced settings to a menu
	* added Remote tab for results of online media (via external apps only)
		* only performs search when the tab is open
		* media list limited by 1000, sortable
		* each media is playable (stream) and preservable
* in Episodes mode of OnlineFeed screen 
	* added sorting for episodes
	* fixed media possibly not playing when feed comes from external app
	* action button is no longer long-pressable
* in topbar of EpisodeInfo screen 
	* added a close button
	* disabled showHome button when episode is from an external app

# 12.3.1

* fixed issues of receiving shared media or feed from other apps
	* fix for YT channels should be combined with update of UT.urn app to 1.0.13

# 12.3.0

* removed hard-coded checks on youtube type
* added PeerTube support via external app PeerPop
* Feed origin filter is amended
* adopted improved API in PodciniLib 1.0.9 in handling external source
	* external app (if used) needs to be updated to 1.0.11 for compatibility
* ensure ShareReceiverActivity exits properly when done and when failed parsing shared external media

# 12.2.2

* in EpisodeInfo view, when Related is clicked, undo closing EpisodeInfo from 12.2.0
* in duplicate list, action button is not shown

# 12.2.1

* in OnlineFeed, fixed shared YT playlist not handled properly 

# 12.2.0

* in EpisodeInfo view, when Related is clicked, ensure EpisodeInfo is closed
* when an external media is shared, if it already exists, show the existing media or a list of existing duplicates
* some dependencies update

# 12.1.13

* when getting episodes from external app, break out early when number of episodes returned is smaller than requested
* PodciniLib upped to 1.0.8, external app (if used) needs to be updated to 1.0.10 for compatibility
	* feed simple updates breaks out more efficiently 
* amended toasts on external app connection
* some code refactoring

# 12.1.12

* fixed wrongly fetching chapters on YT media but not on normal podcast
* added player error handling on corrupted streams
* added extractor factory in player creation pipeline

# 12.1.11

* improved efficiency of opening EpisodeInfo from a list
* ensure to re/de-register searcher from external app when the app is connected/disconnected
* fixed in external app issue of updating YT channel live tab

# 12.1.10

* simplified scroll position handling
* fixed list scroll position not stable on return

# 12.1.9

* on topbar of FeedDetails screen, 
	* enabled History icon in Info mode
	* border is added to feed image 
* in OnlineFeed screen
	* ensure feed options dialog popup only with multiple options
	* episodes limit input is no longer instant (tap on "Setting" is needed)
* ensure viewmodels are cleared on back-press
* in Library screen, fixed strange feeds sorting behavior on feed properties

# 12.1.8

* in OnlineFeed, before subscribe, allows selecting a YT channel tab, currently default or live (streams)
* PodciniLib upped to 1.0.7, external app (if used) needs to be updated to 1.0.9 for compatibility

# 12.1.7

* in OnlineFeed
	* fixed number of episodes showing 0 for normal podcast
	* after subscribe, "Subscribe" button turns to "Open"
	* error message is shown on reopen of an erroneous feed
* in feed full update, enhanced getting episodes in external app
* getting episodes from external app is limited by 5000
* minor code refactoring

# 12.1.6

* fixed null pointer crash in FeedSettings
* in OnlineFeed, fixed episodes count is capped around 100 when feed is provided by external app,
* in feed full update
	* fixed not getting older episodes if limit is set
	* fixed failure of getting updates from external app
* in FeedDetails screen, total episodes in info bar is updated after refresh
* PodciniLib upped to 1.0.6, external app (if used) needs to be updated 1.0.8 for compatibility
* some dependencies update

# 12.1.5

* amended short description for f-doird
* removed org.gradle.workers.isolation.default from gradle.properties, likely a problem for f-droid build

# 12.1.4

* adjusted media buffer a bit to mitigate possible YT media hiccups at higher speed
* ensure to cancel previous data source preparation when new request is launched
* in PlayerDetailed screen
	* fixed the defunct force-video button on the top bar (assembling video stream may take a couple seconds)
	* amended audio-only button on the top video bar (removing video stream and resetting audio stream)
	* note both buttons reset audio/video streams, taking a pause, 
	* setting to audio-only is only necessary to save bandwidth, otherwise, video continues to play when screen is off
* added media title natural sorting algorithm, applied in FeedDetails and Queues screens
* amended logging levels in callFailed
* Kotlin upped to 2.4.0 and krdb to 3.3.4, and other dependencies updates

# 12.1.3

* in FeedDetails screen, removed the redundant feed image in the header
* update screenshots in Readme

# 12.1.2

* dice icon in Queues screen continues to next in queue
* in FeedDetails screen, top-left button opens the drawer (use system back to return)
* in FindFeeds screen
	* top-left button opens the drawer (use system back to return)
	* "Advanced" is changed to a settings icon on the top bar, "Import OPML" option is removed (do that from Settings->Import/Export)
	* "Local" is changed to "Local folder"

# 12.1.1

* in FeedDetails screen, feed image in info bar is moved to the top bar, and toggles list/info modes on click
* dice icon on info bars is padded and transparent

# 12.1.0

* on Infobar of media lists, added dice icon to randomly play an item

# 12.0.7

* removed old db migrations
* no longer logging "No New episodes found for feed"
* built with/for SDK 37

# 12.0.6

* amended feed searcher handling

# 12.0.5

* made binding to external service app more resilient
* clarified toasts when external audio streams are empty
* in OnlineFeed screen
	* enabled swipe actions but only limit to applicable actions (only SearchSelected now)
	* disabled inapplicable menu items in multi-select mode

# 12.0.4

* fixed player possibly playing previous media from external source
* corrected enum class FeedType definition
* added compatibility check when loading an external service app
* PodciniLib upped to 1.0.4

# 12.0.3

* in media player, amended ERROR_CODE_IO_UNSPECIFIED error handling
* fixed local media not playing, adopted from Podcini.X
* PodciniLib upped to 1.0.3

# 12.0.2

* capable of handling multiple external server apps
* PodciniLib upped to 1.0.2

# 12.0.1

* corrected giving direct data source at native player creation
* disabled likely unnecessary uploading duration data when playing an episode
* fixed position not set properly when playing an episode midway

# 12.0.0

* first release
