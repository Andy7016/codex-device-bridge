"""Tk startup wizard and local settings; a network connection is never required."""
from pathlib import Path
import sys
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
import uuid

from .configuration import load_configuration, prepare, update_configuration


def startup_directory():
    if getattr(sys, 'frozen', False):
        return Path(sys.executable).resolve().parent/'devicebridge-data'
    return Path(__file__).resolve().parents[1]/'.local'/'companion-data'


def explain(error):
    if isinstance(error, FileExistsError):
        return '保存目录已存在。请选择新目录，或打开原 host.json；不会覆盖已有身份。'
    messages = {
        'invalid_relay': '中继请填写 wss://域名，或旧版原始TLS的域名/IP；不包含账号、查询参数或其他路径。',
        'invalid_port': '端口应为1至65535的整数。',
        'relay_address_required': '请填写中继地址，或选择稍后配置。',
        'invalid_display_name': '设备名称需要1至80个字符，且不包含控制字符。',
        'local_codex_policy_required': '策略仅支持明确指定的本机 Codex 会话。可先留空完成初始化。',
        'invalid_policy': '会话策略格式不正确；可先留空，不授权会话访问。',
        'identity_files_missing': '请打开完整配置目录中的 host.json，同目录还需要 private.dpapi 和 server.pem。',
        'host_identity_mismatch': '配置与证书身份不一致。请保留原目录并选择完整的原配置。',
        'private_identity_unavailable': 'Windows无法解密本机身份。请使用创建它的Windows用户，或选择其他完整配置。',
    }
    return messages.get(str(error), '配置无法读取或保存。请检查文件格式、证书有效期、保存位置和访问权限；原身份不会自动重建。')


class ConnectionFields:
    def __init__(self, parent, existing=None):
        existing = existing or {}
        self.deferred = tk.BooleanVar(master=parent, value=not bool(existing))
        address = existing.get('host', '')
        if existing.get('transport') == 'wss':
            address = 'wss://' + ('[' + address + ']' if ':' in address else address) + ':' + str(existing.get('port', 443))
        self.host = tk.StringVar(master=parent, value=address)
        self.port = tk.StringVar(master=parent, value=str(existing.get('port', 443)))
        self.certificate = tk.StringVar(master=parent)
        self.system_trust = tk.BooleanVar(master=parent, value=not bool(existing.get('certificatePem')))
        frame = ttk.LabelFrame(parent, text='中继连接（现在可以跳过）', padding=10)
        frame.pack(fill='x', pady=10)
        ttk.Checkbutton(frame, text='稍后配置中继，先完成本机初始化', variable=self.deferred, command=self.toggle).grid(row=0, column=0, columnspan=3, sticky='w')
        ttk.Label(frame, text='WSS地址或域名/IP').grid(row=1, column=0, sticky='w', pady=5)
        host = ttk.Entry(frame, textvariable=self.host, width=42)
        host.grid(row=1, column=1, sticky='ew')
        ttk.Label(frame, text='端口').grid(row=2, column=0, sticky='w', pady=5)
        port = ttk.Entry(frame, textvariable=self.port, width=12)
        port.grid(row=2, column=1, sticky='w')
        trust = ttk.Checkbutton(frame, text='使用系统信任的证书（自签名证书请取消勾选）', variable=self.system_trust, command=self.toggle)
        trust.grid(row=3, column=0, columnspan=3, sticky='w', pady=5)
        cert = ttk.Entry(frame, textvariable=self.certificate, width=42)
        cert.grid(row=4, column=1, sticky='ew')
        ttk.Label(frame, text='中继证书').grid(row=4, column=0, sticky='w')
        browse = ttk.Button(frame, text='选择', command=self.choose_certificate)
        browse.grid(row=4, column=2, padx=5)
        ttk.Label(frame, text='已有自签名证书可留空沿用。创建和保存均不测试服务器可达性。', wraplength=560).grid(row=5, column=0, columnspan=3, sticky='w', pady=(6, 0))
        frame.columnconfigure(1, weight=1)
        self.endpoint_widgets, self.cert_widgets = (host, port, trust), (cert, browse)
        self.toggle()

    def choose_certificate(self):
        path = filedialog.askopenfilename(title='选择中继公开证书', filetypes=[('证书', '*.pem *.crt'), ('所有文件', '*')])
        if path:
            self.certificate.set(path)

    def toggle(self):
        for widget in self.endpoint_widgets:
            widget.configure(state='disabled' if self.deferred.get() else 'normal')
        for widget in self.cert_widgets:
            widget.configure(state='disabled' if self.deferred.get() or self.system_trust.get() else 'normal')

    def values(self):
        if self.deferred.get():
            return {'relay_host': None, 'relay_port': 443, 'relay_certificate': None}
        if not self.host.get().strip():
            raise ValueError('relay_address_required')
        try:
            port = int(self.port.get())
        except ValueError:
            raise ValueError('invalid_port') from None
        return {'relay_host': self.host.get().strip(), 'relay_port': port,
                'relay_certificate': None if self.system_trust.get() else self.certificate.get().strip() or None}


class StartupWizard:
    def __init__(self, root, directory):
        self.root, self.directory, self.selected = root, Path(directory), None
        root.title('Device Bridge · 首次启动')
        root.geometry('790x720')
        self.body = ttk.Frame(root, padding=24)
        self.body.pack(fill='both', expand=True)
        ttk.Label(self.body, text='先创建这台电脑的身份', font=('Microsoft YaHei UI', 19, 'bold')).pack(anchor='w')
        ttk.Label(self.body, text='没有 host.json 或云服务器，也可以完成初始化。稍后再配置连接。').pack(anchor='w', pady=8)
        self.name = tk.StringVar(master=root, value='我的电脑')
        self.output = tk.StringVar(master=root, value=str(self.directory/('computer-'+uuid.uuid4().hex[:8])))
        self.policy = tk.StringVar(master=root)
        self.row('电脑名称', self.name)
        self.row('新配置目录', self.output, self.choose_directory)
        self.row('会话策略（可选）', self.policy, self.choose_policy)
        ttk.Label(self.body, text='策略留空时创建空白策略，不授权访问任何会话。已有策略会复制快照。').pack(anchor='w', pady=4)
        self.connection = ConnectionFields(self.body)
        self.create_button = ttk.Button(self.body, text='创建 host.json 并打开', command=self.create)
        self.create_button.pack(fill='x', pady=8)
        ttk.Separator(self.body).pack(fill='x', pady=8)
        ttk.Button(self.body, text='打开已有 host.json', command=self.import_existing).pack(fill='x')
        ttk.Label(self.body, text='已有配置请保留完整目录及原Windows用户。初始化不会覆盖已有身份或修改VPN。').pack(anchor='w', pady=10)
        root.protocol('WM_DELETE_WINDOW', root.quit)

    def row(self, label, variable, command=None):
        frame = ttk.Frame(self.body)
        frame.pack(fill='x', pady=5)
        ttk.Label(frame, text=label, width=18).pack(side='left')
        ttk.Entry(frame, textvariable=variable).pack(side='left', fill='x', expand=True)
        if command:
            ttk.Button(frame, text='选择', command=command).pack(side='left', padx=(5, 0))

    def choose_directory(self):
        path = filedialog.askdirectory(title='选择新配置目录的保存位置')
        if path:
            self.output.set(str(Path(path)/('computer-'+uuid.uuid4().hex[:8])))

    def choose_policy(self):
        path = filedialog.askopenfilename(title='选择已有会话策略', filetypes=[('JSON策略', '*.json')])
        if path:
            self.policy.set(path)

    def create(self):
        try:
            if not self.output.get().strip():
                raise ValueError('invalid_configuration')
            values = self.connection.values()
            if not self.connection.deferred.get() and not self.connection.system_trust.get() and not values['relay_certificate']:
                raise ValueError('invalid_configuration')
            result = prepare(self.output.get(), self.policy.get().strip() or None, display_name=self.name.get().strip(), **values)
            self.selected = Path(result['directory'])/'host.json'
            self.root.quit()
        except Exception as error:
            messagebox.showerror('初始化未完成', explain(error), parent=self.root)

    def import_existing(self):
        path = filedialog.askopenfilename(title='打开完整配置目录中的 host.json', filetypes=[('本机配置', '*.json')])
        if path:
            try:
                load_configuration(path)
                self.selected = Path(path).resolve()
                self.root.quit()
            except Exception as error:
                messagebox.showerror('配置未导入', explain(error), parent=self.root)


class SettingsDialog:
    def __init__(self, root, path):
        self.path, self.saved = Path(path), False
        config, _ = load_configuration(path)
        self.existing_certificate = bool((config['identity'].get('relay') or {}).get('certificatePem'))
        self.window = tk.Toplevel(root)
        self.window.title('连接与会话范围设置')
        self.window.geometry('750x440')
        self.window.transient(root)
        body = ttk.Frame(self.window, padding=18)
        body.pack(fill='both', expand=True)
        ttk.Label(body, text='保存后重新载入配置，保留电脑身份、设备授权和恢复记录。').pack(anchor='w')
        self.connection = ConnectionFields(body, config['identity'].get('relay'))
        self.policy = tk.StringVar(master=self.window)
        row = ttk.Frame(body)
        row.pack(fill='x', pady=8)
        ttk.Label(row, text='导入会话策略（可选）').pack(side='left')
        ttk.Entry(row, textvariable=self.policy).pack(side='left', fill='x', expand=True)
        ttk.Button(row, text='选择', command=self.choose_policy).pack(side='left')
        ttk.Label(body, text='留空保留当前策略；导入会保存副本，不修改源策略。').pack(anchor='w')
        ttk.Button(body, text='保存并重新载入', command=self.save).pack(fill='x', pady=10)
        self.window.grab_set()

    def choose_policy(self):
        path = filedialog.askopenfilename(parent=self.window, title='选择已有会话策略', filetypes=[('JSON策略', '*.json')])
        if path:
            self.policy.set(path)

    def save(self):
        try:
            values = self.connection.values()
            if not self.connection.deferred.get() and not self.connection.system_trust.get() and not values['relay_certificate'] and not self.existing_certificate:
                raise ValueError('invalid_configuration')
            update_configuration(self.path, **values, policy=self.policy.get().strip() or None,
                                 use_system_trust=self.connection.system_trust.get())
            self.saved = True
            self.window.destroy()
        except Exception as error:
            messagebox.showerror('设置未保存', explain(error), parent=self.window)
