package spam.blocker.ui.setting

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.registerReceiver
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import il.co.theblitz.observablecollections.enums.ObservableCollectionsAction.Add
import il.co.theblitz.observablecollections.enums.ObservableCollectionsAction.AddAll
import il.co.theblitz.observablecollections.enums.ObservableCollectionsAction.Clear
import il.co.theblitz.observablecollections.enums.ObservableCollectionsAction.RemoveAt
import il.co.theblitz.observablecollections.enums.ObservableCollectionsAction.Set
import il.co.theblitz.observablecollections.lists.ObservableArrayList
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import spam.blocker.R
import spam.blocker.databinding.SettingFragmentBinding
import spam.blocker.db.ContentRuleTable
import spam.blocker.db.NumberRuleTable
import spam.blocker.db.PatternRule
import spam.blocker.db.QuickCopyRuleTable
import spam.blocker.db.RuleTable
import spam.blocker.def.Def
import spam.blocker.ui.util.TimeRangePicker
import spam.blocker.ui.util.Util.Companion.applyTheme
import spam.blocker.ui.util.Util.Companion.setupImageTooltip
import spam.blocker.util.Launcher
import spam.blocker.util.Permission
import spam.blocker.util.Permissions
import spam.blocker.util.Permissions.Companion.isCallLogPermissionGranted
import spam.blocker.util.Permissions.Companion.isContactsPermissionGranted
import spam.blocker.util.Permissions.Companion.isReadSmsPermissionGranted
import spam.blocker.util.PermissionChain
import spam.blocker.util.SharedPref
import spam.blocker.util.Util


class SettingFragment : Fragment() {

    private var _binding: SettingFragmentBinding? = null
    private val binding get() = _binding!!

    private var recentApps = ObservableArrayList<String>()

    private var numberRules = ObservableArrayList<PatternRule>()
    private var contentRules = ObservableArrayList<PatternRule>()
    private var quickCopyRules = ObservableArrayList<PatternRule>()

    private lateinit var onEnabledChange: () -> Unit

    @SuppressLint("Range", "ClickableViewAccessibility", "NotifyDataSetChanged")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SettingFragmentBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val ctx = requireContext()
        val spf = SharedPref(ctx)


        // global enable switch
        val switchGlobalEnable = root.findViewById<SwitchCompat>(R.id.switch_globally_enabled)
        val group_quick_filters = root.findViewById<RelativeLayout>(R.id.group_quick_filters)
        val group_regex_filters = root.findViewById<RelativeLayout>(R.id.group_regex_filters)
        onEnabledChange = {
            val enabled = spf.isGloballyEnabled()
            if (switchGlobalEnable.isChecked != enabled)
                switchGlobalEnable.isChecked = enabled
            requireActivity().window.statusBarColor = ContextCompat.getColor(
                ctx,
                if (enabled) R.color.dark_sea_green else R.color.salmon
            )
            group_quick_filters.visibility = if (enabled) View.VISIBLE else View.GONE
            group_regex_filters.visibility = if (enabled) View.VISIBLE else View.GONE
        }
        onEnabledChange()
        switchGlobalEnable.setOnClickListener {
            spf.toggleGloballyEnabled()
            onEnabledChange()
        }

        // theme switch
        val switchTheme = root.findViewById<SwitchCompat>(R.id.switch_theme)
        val dark = spf.isDarkTheme()
        switchTheme.isChecked = dark
        switchTheme.setOnClickListener {
            spf.toggleDarkTheme()
            applyTheme(spf.isDarkTheme())
        }

        // backup / restore
        val btn_backup = root.findViewById<MaterialButton>(R.id.btn_backup)
        btn_backup.setOnClickListener {
            PopupBackupFragment().show(requireActivity().supportFragmentManager, "tag_backup")
        }

        setupContacts(root)

        setupRepeatedCall(root)

        setupDialed(root)

        clearUninstalledRecentApps()
        setupRecentApps(root)

        setupSilenceCall(root)

        setupOffTime(root)

        setupRules(
            root,
            R.id.recycler_number_filters,
            R.id.btn_add_number_filter,
            R.id.btn_test_number_filters,
            numberRules,
            NumberRuleTable(),
            Def.ForCall
        )
        setupRules(
            root,
            R.id.recycler_content_filters,
            R.id.btn_add_content_filter,
            R.id.btn_test_content_filters,
            contentRules,
            ContentRuleTable(),
            Def.ForSms
        )
        setupRules(
            root,
            R.id.recycler_quick_copy,
            R.id.btn_add_quick_copy,
            R.id.btn_test_quick_copy,
            quickCopyRules,
            QuickCopyRuleTable(),
            Def.ForQuickCopy
        )
        // tooltips
        setupTooltips(root)

//        handleToggledByTile(root)

        return root
    }


    @SuppressLint("SetTextI18n")
    private fun setupRepeatedCall(root: View) {
        val ctx = requireContext()
        val spf = SharedPref(ctx)
        val btn_config = root.findViewById<MaterialButton>(R.id.btn_config_repeated_call)
        val switchRepeatedEnabled = root.findViewById<SwitchCompat>(R.id.switch_allow_repeated_call)

        fun updateButton() {
            val (times, inXMin) = spf.getRepeatedConfig()
            val labelTimes =
                resources.getString(if (times == 1) R.string.time else R.string.times)
            val labelMin = resources.getString(R.string.min)
            btn_config.text = "$times $labelTimes / $inXMin $labelMin"
            btn_config.visibility = if (spf.isRepeatedCallEnabled() && isCallLogPermissionGranted(ctx))
                View.VISIBLE else View.GONE
        }


        updateButton()
        btn_config.setOnClickListener {
            PopupRepeatedConfigFragment { times: Int, inXMin: Int ->
                spf.setRepeatedConfig(times, inXMin)
                updateButton()
            }.show(requireActivity().supportFragmentManager, "tag_config_repeated")
        }

        switchRepeatedEnabled.isChecked = spf.isRepeatedCallEnabled()
                && isCallLogPermissionGranted(ctx)

        val permChain = PermissionChain(this,
            listOf(
                Permission(Manifest.permission.READ_CALL_LOG),
                Permission(Manifest.permission.READ_SMS, true)
            )
        ) { allGranted ->
            switchRepeatedEnabled.isChecked = allGranted
            spf.setRepeatedCallEnabled(allGranted)
            updateButton()
        }

        switchRepeatedEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (isCallLogPermissionGranted(ctx)) { // already granted
                    spf.setRepeatedCallEnabled(true)
                } else {
                    permChain.ask()
                }
            } else {
                spf.setRepeatedCallEnabled(false)
            }
            updateButton()
        }
    }
    private fun setupDialed(root: View) {
        val ctx = requireContext()
        val spf = SharedPref(ctx)
        val btn_config = root.findViewById<MaterialButton>(R.id.btn_config_dialed)
        val switchEnabled = root.findViewById<SwitchCompat>(R.id.switch_enable_dialed)

        fun updateButton() {
            val nDay = spf.getDialedConfig()
            val labelDays =
                resources.getString(if (nDay > 1) R.string.days else R.string.day)
            btn_config.text = "$nDay $labelDays"
            btn_config.visibility = if (spf.isDialedEnabled() && isCallLogPermissionGranted(ctx))
                View.VISIBLE else View.GONE
        }


        updateButton()
        btn_config.setOnClickListener {
            PopupDialedConfigFragment { inXDays: Int ->
                spf.setDialedConfig(inXDays)
                updateButton()
            }.show(requireActivity().supportFragmentManager, "tag_config_dialed")
        }

        switchEnabled.isChecked = spf.isDialedEnabled()
                && isCallLogPermissionGranted(ctx)

        val permChain = PermissionChain(this,
            listOf(
                Permission(Manifest.permission.READ_CALL_LOG),
                Permission(Manifest.permission.READ_SMS, true)
            )
        ) { allGranted ->
            switchEnabled.isChecked = allGranted
            spf.setDialedEnabled(allGranted)
            updateButton()
        }

        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (isCallLogPermissionGranted(ctx)) { // already granted
                    spf.setDialedEnabled(true)
                } else {
                    permChain.ask()
                }
            } else {
                spf.setDialedEnabled(false)
            }
            updateButton()
        }
    }

    private fun setupContacts(root: View) {
        val ctx = requireContext()
        val spf = SharedPref(ctx)

        // maybe it has been turned off in settings
        if (!isContactsPermissionGranted(ctx)) {
            spf.setContactEnabled(false)
        }

        val btnInclusive = root.findViewById<MaterialButton>(R.id.btn_config_contact)
        val switchContactEnabled = root.findViewById<SwitchCompat>(R.id.switch_permit_contacts)


        fun updateButton() {
            val isExclusive = spf.isContactExclusive()
            btnInclusive.visibility = if (spf.isContactEnabled()) View.VISIBLE else View.GONE

            val color = if (isExclusive) R.color.salmon else R.color.mid_grey
            btnInclusive.setTextColor(resources.getColor(color, null))
            btnInclusive.setStrokeColorResource(color)
            btnInclusive.text =
                if (isExclusive) ctx.resources.getString(R.string.exclusive) else ctx.resources.getString(
                    R.string.inclusive
                )
        }

        updateButton()

        btnInclusive.setOnClickListener {
            spf.toggleContactExclusive()
            updateButton()
        }

        switchContactEnabled.isChecked = spf.isContactEnabled() && isContactsPermissionGranted(ctx)

        val permChain = PermissionChain(this,
            listOf(
                Permission(Manifest.permission.READ_CONTACTS)
            )
        ) { allGranted ->
            switchContactEnabled.isChecked = allGranted
            spf.setContactEnabled(allGranted)
            updateButton()
        }

        switchContactEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (isContactsPermissionGranted(ctx)) { // already granted
                    spf.setContactEnabled(true)
                } else {
                    permChain.ask()
                }
            } else {
                spf.setContactEnabled(false)
            }
            updateButton()
        }
    }

    // Clear uninstalled apps from the recent apps list
    private fun clearUninstalledRecentApps() {
        val ctx = requireContext()
        val spf = SharedPref(ctx)

        val cleared = spf.getRecentAppList().filter {
            Util.isPackageInstalled(ctx, it)
        }
        spf.setRecentAppList(cleared)
    }


    @SuppressLint("NotifyDataSetChanged", "ClickableViewAccessibility")
    private fun setupRecentApps(root: View) {
        val ctx = requireContext()
        val spf = SharedPref(ctx)

        // config
        val btn_config = root.findViewById<MaterialButton>(R.id.btn_config_recent_apps)
        val recyclerAppIcons = root.findViewById<RecyclerView>(R.id.recycler_app_icons)

        // update button and recycler
        fun updateUI() {
            val visibility = if (recentApps.size > 0 && Permissions.isUsagePermissionGranted(ctx)) View.VISIBLE else View.GONE
            val inXmin = spf.getRecentAppConfig()
            btn_config.text = "$inXmin ${resources.getString(R.string.min)}"
            btn_config.visibility = visibility

            recyclerAppIcons.visibility = visibility
        }
        updateUI()
        btn_config.setOnClickListener {
            PopupRecentAppConfigFragment { inXMin: Int ->
                spf.setRecentAppConfig(inXMin)
                updateUI()
            }.show(requireActivity().supportFragmentManager, "tag_config_recent_app")
        }


        recentApps.clear()
        recentApps.addAll(spf.getRecentAppList())

        // recycler
        val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            updateUI()
        }
        fun popupRecentApps() {
            if (!Permissions.isUsagePermissionGranted(ctx)) {
                AlertDialog.Builder(ctx)
                    .setMessage(ctx.resources.getString(R.string.prompt_go_to_usage_permission_setting))
                    .setPositiveButton(ctx.resources.getString(R.string.ok)) { dialog, _ ->
                        dialog.dismiss()

                        launcher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                    .setNegativeButton(ctx.resources.getString(R.string.cancel)) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
                return
            }
            val dialog = PopupAppListFragment(recentApps)
            dialog.show(requireActivity().supportFragmentManager, "tag_select_apps")
        }

        val adapterRecentApps = AppListAdapter(ctx, recentApps) { popupRecentApps() }
        recentApps.observe(viewLifecycleOwner) {
            updateUI()
            spf.setRecentAppList(recentApps.toList())
            when (it.action) {
                Add -> adapterRecentApps.notifyItemInserted(it.actionInt!!)
                AddAll -> adapterRecentApps.notifyDataSetChanged()
                Set -> adapterRecentApps.notifyItemChanged(it.actionInt!!)
                Clear -> adapterRecentApps.notifyDataSetChanged()
                RemoveAt -> adapterRecentApps.notifyItemRemoved(it.actionInt!!)

                else -> {}
            }
        }

        recyclerAppIcons.setAdapter(adapterRecentApps)
        recyclerAppIcons.setLayoutManager(
            LinearLayoutManager(ctx, RecyclerView.HORIZONTAL, false))

        val btnSelectApp = root.findViewById<ImageButton>(R.id.btn_select_app)
        btnSelectApp.setOnClickListener {
            popupRecentApps()
        }
    }

    private fun setupSilenceCall(root: View) {
        val ctx = requireContext()
        val spf = SharedPref(ctx)
        val switchEnabled = root.findViewById<SwitchCompat>(R.id.switch_enable_silence_call)

        switchEnabled.isChecked = spf.isSilenceCallEnabled()

        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            spf.setSilenceCallEnabled(isChecked)
        }
    }

    private fun setupOffTime(root: View) {
        val ctx = requireContext()
        val spf = SharedPref(ctx)
        val switchEnabled = root.findViewById<SwitchCompat>(R.id.switch_enable_off_time)
        val btn = root.findViewById<MaterialButton>(R.id.btn_off_time)

        switchEnabled.isChecked = spf.isOffTimeEnabled()

        fun updateButton() {
            val (sHour, sMin) = spf.getOffTimeStart()
            val (eHour, eMin) = spf.getOffTimeEnd()

            btn.text = Util.formatTimeRange(ctx, sHour, sMin, eHour, eMin)
            btn.visibility = if (spf.isOffTimeEnabled()) View.VISIBLE else View.GONE
        }
        updateButton()
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            spf.setOffTimeEnabled(isChecked)
            updateButton()
        }
        btn.setOnClickListener {
            val (startH, startM) = spf.getOffTimeStart()
            val (endH, endM) = spf.getOffTimeEnd()

            TimeRangePicker(this, startH, startM, endH, endM) { stH, stM, etH, etM ->
                spf.setOffTimeStart(stH, stM)
                spf.setOffTimeEnd(etH, etM)
                updateButton()
            }.show()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @SuppressLint("NotifyDataSetChanged")
    private fun setupRules(
        root: View,
        recyclerId: Int, addBtnId: Int, testBtnId: Int,
        filters: ObservableArrayList<PatternRule>,
        dbTable: RuleTable,
        forRuleType: Int
    ) {
        val ctx = requireContext()

        val recycler = root.findViewById<RecyclerView>(recyclerId)
        val layoutManager = LinearLayoutManager(ctx, RecyclerView.VERTICAL, false)
        recycler.setLayoutManager(layoutManager)

        fun asyncReloadFromDb() {
            GlobalScope.launch(Dispatchers.IO) {
                val records = dbTable.listAll(ctx)
                withContext(Dispatchers.Main) {
                    filters.clear()
                    filters.addAll(records)
                }
            }
        }
        asyncReloadFromDb()

        val onItemClick = { clickedF: PatternRule -> Unit
            val i = filters.indexOf(clickedF)

            val dialog = PopupEditFilterFragment(
                filters[i], { newF -> // on save button clicked
                    // 1. update this filter in memory
                    val existing = filters[i]
                    existing.pattern = newF.pattern
                    existing.patternExtra = newF.patternExtra
                    existing.description = newF.description
                    existing.flagCallSms = newF.flagCallSms
                    existing.isBlacklist = newF.isBlacklist

                    // 2. update in db
                    dbTable.updatePatternRule(ctx, existing.id, existing)

                    // 3. gui update
                    asyncReloadFromDb()
                }, forRuleType
            )

            dialog.show(requireActivity().supportFragmentManager, "tag_edit_filter")
        }
        val onItemLongClick = { clickedF: PatternRule ->
            val i = filters.indexOf(clickedF)
            val viewHolder = recycler.findViewHolderForAdapterPosition(i);
            val popup = PopupMenu(ctx, viewHolder?.itemView)
            popup.menuInflater.inflate(R.menu.rule_context_menu, popup.menu)
            popup.setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.rule_clone -> {
                        // 1. add to db
                        dbTable.addNewPatternRule(ctx, clickedF)

                        // 2. refresh gui
                        asyncReloadFromDb()
                    }
                }
                false
            }
            popup.show()
        }

        val adapter = RuleAdapter(ctx, onItemClick, onItemLongClick, filters, forRuleType)
        recycler.setAdapter(adapter)

        filters.observe(viewLifecycleOwner) {
            when (it.action) {
                Add -> adapter.notifyItemInserted(it.actionInt!!)
                AddAll -> adapter.notifyDataSetChanged()
                Set -> adapter.notifyItemChanged(it.actionInt!!)
                Clear -> adapter.notifyDataSetChanged()
                RemoveAt -> adapter.notifyItemRemoved(it.actionInt!!)
                else -> {}
            }
        }

        val btnAdd = root.findViewById<MaterialButton>(addBtnId)
        btnAdd.setOnClickListener {
            val dialog = PopupEditFilterFragment(
                PatternRule(), { newF -> // callback
                    // 1. add to db
                    dbTable.addNewPatternRule(ctx, newF)

                    // 2. refresh gui
                    asyncReloadFromDb()
                }, forRuleType
            )

            dialog.show(requireActivity().supportFragmentManager, "tag_edit_filter")
        }
        val btnTest = root.findViewById<MaterialButton>(testBtnId)
        btnTest.setOnClickListener {
            PopupTestFragment(forRuleType)
                .show(requireActivity().supportFragmentManager, "tag_test")
        }

        val swipeLeftCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                rv: RecyclerView,
                h: RecyclerView.ViewHolder,
                t: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val i = viewHolder.absoluteAdapterPosition
                val fToDel = filters[i]

                // 1. delete from db
                dbTable.delPatternRule(ctx, fToDel.id)

                // 2. remove from ArrayList
                filters.removeAt(i)

                // 3. show snackbar
                Snackbar.make(recycler, fToDel.pattern, Snackbar.LENGTH_LONG).setAction(
                    resources.getString(R.string.undelete),
                ) {
                    dbTable.addPatternRuleWithId(ctx, fToDel)
                    filters.add(i, fToDel)
                }.show()
            }
        }
        ItemTouchHelper(swipeLeftCallback).attachToRecyclerView(recycler)

    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        numberRules.removeObservers(viewLifecycleOwner)
        recentApps.removeObservers(viewLifecycleOwner)

    }

    private fun setupTooltips(root: View) {
        val ctx = requireContext()
        setupImageTooltip(
            ctx, viewLifecycleOwner, root.findViewById(R.id.setting_help_globally_enabled),
            R.string.help_globally_enabled
        )
        setupImageTooltip(
            ctx, viewLifecycleOwner, root.findViewById(R.id.setting_help_enable_contacts),
            R.string.help_contact
        )
        setupImageTooltip(
            ctx, viewLifecycleOwner, root.findViewById(R.id.setting_help_repeated_call),
            R.string.help_repeated_call
        )
        setupImageTooltip(
            ctx, viewLifecycleOwner, root.findViewById(R.id.setting_help_dialed),
            R.string.help_dialed
        )
        setupImageTooltip(
            ctx, viewLifecycleOwner, root.findViewById(R.id.setting_help_recent_apps),
            R.string.help_recent_apps
        )
        setupImageTooltip(
            ctx, viewLifecycleOwner, root.findViewById(R.id.setting_help_silence_call),
            R.string.help_silence_call
        )
        setupImageTooltip(
            ctx, viewLifecycleOwner, root.findViewById(R.id.setting_help_number_filter),
            R.string.help_number_filter
        )
        setupImageTooltip(
            ctx, viewLifecycleOwner, root.findViewById(R.id.setting_help_sms_content_filter),
            R.string.help_sms_content_filter
        )
        setupImageTooltip(
            ctx, viewLifecycleOwner, root.findViewById(R.id.setting_help_quick_copy),
            R.string.help_quick_copy
        )
        setupImageTooltip(
            ctx, viewLifecycleOwner, root.findViewById(R.id.setting_help_off_time),
            R.string.help_off_time
        )
    }

    // Synchronize the UI on Tile clicking
    private lateinit var broadcastReceiver: BroadcastReceiver

    override fun onResume() {
        super.onResume()

        onEnabledChange()

        // handle tile click event, restart app
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action
                if (action == Def.ACTION_TILE_TOGGLE) {
                    onEnabledChange()
                }
            }
        }

        val lbm = LocalBroadcastManager.getInstance(requireContext())
        lbm.registerReceiver(broadcastReceiver, IntentFilter(Def.ACTION_TILE_TOGGLE))
    }

    override fun onPause() {
        super.onPause()

        val lbm = LocalBroadcastManager.getInstance(requireContext())
        lbm.unregisterReceiver(broadcastReceiver)
    }
}