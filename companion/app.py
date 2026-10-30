"""Local Windows pairing window; no HTTP administration endpoint."""
import argparse
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

import qrcode
from PIL import ImageTk
from . import __version__
from .host import Host
from .configuration import remember_configuration, remembered_configuration
from .startup import SettingsDialog, StartupWizard, explain, startup_directory


class Window:
    def __init__(self, root, host):
        self.root, self.host, self.invitation = root, host, None
        self.update_expires = None
        self.closing = False
        self.reopen_config = None
        root.title('Device Bridge '+__version__+' · 电脑连接')
        root.geometry('1100x950')
        body = ttk.Frame(root, padding=20)
        body.pack(fill='both', expand=True)
        ttk.Label(body, text='连接你的手机', font=('Microsoft YaHei UI', 20, 'bold')).pack(anchor='w')
        ttk.Label(body, text='扫码后核对两端六位数字，在本机选择允许访问的范围。').pack(anchor='w', pady=8)
        self.status = ttk.Label(body, wraplength=1000)
        self.status.pack(anchor='w')
        ttk.Label(body, text='当前配置：'+str(host.config_path), wraplength=1000).pack(anchor='w', pady=(4, 8))
        controls = ttk.Frame(body)
        controls.pack(fill='x', pady=5)
        ttk.Button(controls, text='连接与会话范围设置', command=self.settings).pack(side='left')
        ttk.Button(controls, text='新建配置', command=self.new_configuration).pack(side='left', padx=8)
        ttk.Button(controls, text='打开其他 host.json', command=self.open_configuration).pack(side='left')
        ttk.Button(controls, text='导出WSS中继登记', command=self.export_registration).pack(side='left', padx=8)
        if not host.identity.get('relay'):
            ttk.Label(body, text='本机身份已准备好。尚未配置中继，暂不能扫码连接；可先保留配置，稍后补填。', wraplength=950).pack(anchor='w', pady=6)
        panel = ttk.Frame(body)
        panel.pack(fill='x', pady=12)
        left, right = ttk.Frame(panel), ttk.Frame(panel)
        left.pack(side='left', padx=(0, 20))
        right.pack(side='left', fill='both', expand=True)
        self.qr = ttk.Label(left, text='点击生成配对二维码', anchor='center', width=40)
        self.qr.pack()
        self.countdown = ttk.Label(left)
        self.countdown.pack(pady=5)
        self.invite_button = ttk.Button(left, text='生成新的配对二维码', command=self.new_invite,
                                       state='normal' if host.identity.get('relay') else 'disabled')
        self.invite_button.pack(fill='x')
        ttk.Button(left, text='取消当前邀请', command=self.cancel_invite).pack(fill='x', pady=4)
        ttk.Label(right, text='待确认手机', font=('Microsoft YaHei UI', 12, 'bold')).pack(anchor='w')
        self.pending = tk.Listbox(right, height=3, exportselection=False)
        self.pending.pack(fill='x', pady=8)
        ttk.Label(right, text='授权会话（“发送”可触发原会话行为）').pack(anchor='w')
        self.grants = {}
        for label, target in host.service.policy.get('targets', {}).items():
            if target.get('kind') != 'codex' or 'read' not in target.get('rights', []):
                continue
            row = ttk.Frame(right)
            row.pack(fill='x', pady=3)
            read, send = tk.BooleanVar(value=True), tk.BooleanVar(value=False)
            ttk.Checkbutton(row, text=str(target.get('displayName', label))[:30], variable=read).pack(side='left')
            ttk.Checkbutton(row, text='发送', variable=send, state='normal' if 'send' in target.get('rights', []) else 'disabled').pack(side='right')
            self.grants[label] = (read, send)
        if not self.grants:
            ttk.Label(right, text='尚未导入会话策略，当前不授权访问任何会话。\n可在“连接与会话范围设置”中导入已有策略。', wraplength=430).pack(anchor='w', pady=8)
        ttk.Button(right, text='数字一致，确认配对', command=self.approve).pack(fill='x', pady=(12, 4))
        ttk.Button(right, text='拒绝所选请求', command=self.deny).pack(fill='x')
        ttk.Label(body, text='已配对设备', font=('Microsoft YaHei UI', 12, 'bold')).pack(anchor='w', pady=(12, 4))
        self.devices = tk.Listbox(body, height=5, exportselection=False)
        self.devices.pack(fill='x')
        ttk.Button(body, text='撤销所选设备', command=self.revoke).pack(anchor='e', pady=8)
        ttk.Button(body, text='为所选设备生成连接更新二维码', command=self.connection_update).pack(anchor='e', pady=4)
        ttk.Label(body, text='电脑身份：'+host.identity['hostId'][:24]+'…').pack(anchor='w')
        ttk.Label(body, text='关闭窗口会停止服务。中继仅转发端到端加密流量。').pack(anchor='w', pady=8)
        root.protocol('WM_DELETE_WINDOW', self.close)
        self.pending_rows, self.device_rows = [], []
        self.refresh()

    def new_invite(self):
        try:
            self.update_expires = None
            self.invitation = self.host.pairing.invite()
            image = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_L, box_size=3, border=4)
            image.add_data(self.invitation['text'])
            image.make(fit=True)
            self.photo = ImageTk.PhotoImage(image.make_image().get_image())
            self.qr.configure(image=self.photo, text='')
        except Exception:
            messagebox.showerror('无法创建邀请', '本机身份或配对存储不可用。')

    def settings(self):
        dialog = SettingsDialog(self.root, self.host.config_path)
        self.root.wait_window(dialog.window)
        if dialog.saved:
            self.reopen_config = self.host.config_path
            self.close()

    def connection_update(self):
        selection = self.devices.curselection()
        if not selection:
            return
        try:
            self.cancel_invite()
            text = self.host.connection_update(self.device_rows[selection[0]]['device'])
            image = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_L, box_size=3, border=4)
            image.add_data(text); image.make(fit=True)
            self.photo = ImageTk.PhotoImage(image.make_image().get_image())
            self.qr.configure(image=self.photo, text='')
            self.update_expires = int(time.time()) + 120
            self.countdown.configure(text='连接更新有效期120秒；手机扫码后沿用原身份和操作记录。')
        except Exception:
            messagebox.showerror('无法更新连接', '请选择仍获授权的设备，并在设置中填写WSS中继地址。')

    def export_registration(self):
        from .admission import registration
        from .configuration import write_json
        path = filedialog.asksaveasfilename(parent=self.root, title='导出中继登记（不含电脑私钥或原始凭据）',
            initialdir=str(self.host.config_path.parent), initialfile='relay-registration-wss.json',
            defaultextension='.json', filetypes=[('JSON', '*.json')])
        if path:
            try:
                write_json(path, registration(self.host.identity['serverCertificatePem'], self.host.private['relayToken']))
                messagebox.showinfo('登记已导出', '将此文件的完整JSON填入中继的RELAY_REGISTRATIONS_JSON配置。不要上传private.dpapi。', parent=self.root)
            except Exception:
                messagebox.showerror('导出未完成', '无法保存中继登记，请检查输出位置。', parent=self.root)

    def new_configuration(self):
        self.reopen_config = ''  # return to the wizard after releasing the old journal lease
        self.close()

    def open_configuration(self):
        from .configuration import load_configuration
        path = filedialog.askopenfilename(parent=self.root, title='打开完整配置目录中的 host.json',
            initialdir=str(self.host.config_path.parent), initialfile='host.json', filetypes=[('本机配置', '*.json')])
        if path:
            try:
                load_configuration(path)
                self.reopen_config = path
                self.close()
            except Exception as error:
                messagebox.showerror('配置未导入', explain(error), parent=self.root)

    def cancel_invite(self):
        self.update_expires = None
        if self.invitation:
            self.host.pairing.deny(self.invitation['packet']['inviteId'])
        self.invitation = None
        self.qr.configure(image='', text='邀请已取消')

    def approve(self):
        selection = self.pending.curselection()
        if not selection:
            return
        row = self.pending_rows[selection[0]]
        grants = {label: ['read']+(['send'] if send.get() else []) for label, (read, send) in self.grants.items() if read.get()}
        if not grants:
            messagebox.showinfo('请选择范围', '至少选择一条允许读取的会话。')
            return
        if not messagebox.askyesno('确认配对', f"手机：{row['phone_name']}\n核对码：{row['code']}\n请确认手机显示相同数字。"):
            return
        try:
            self.host.approve(row['id'], row['code'], grants)
            self.invitation = None
            self.qr.configure(image='', text='配对已完成')
        except Exception:
            messagebox.showerror('配对未完成', '邀请可能已过期、被取消或身份已登记。请保留手机记录并核对状态。')

    def deny(self):
        selection = self.pending.curselection()
        if selection:
            self.host.pairing.deny(self.pending_rows[selection[0]]['id'])

    def revoke(self):
        selection = self.devices.curselection()
        if not selection:
            return
        row = self.device_rows[selection[0]]
        if row['enabled'] and messagebox.askyesno('撤销手机', '停止这部手机的后续读取和发送？已经交接的任务可能继续执行。'):
            try:
                self.host.revoke(row['device'])
            except Exception:
                messagebox.showerror('撤销状态待核对', '请停止服务并检查本机状态。')

    def refresh(self):
        if self.closing:
            return
        self.status.configure(text=self.host.status)
        pending = self.host.pairing.pending()
        if pending != self.pending_rows:
            self.pending_rows = pending
            self.pending.delete(0, 'end')
            for row in pending:
                self.pending.insert('end', row['phone_name']+'    '+row['code'])
            if pending:
                self.pending.selection_set(0)
        devices = self.host.pairing.devices()
        if devices != self.device_rows:
            self.device_rows = devices
            self.devices.delete(0, 'end')
            for row in devices:
                self.devices.insert('end', row['phone_name']+' · '+('已授权' if row['enabled'] else '已撤销')+' · '+row['device'][6:18])
        if self.invitation:
            left = self.invitation['packet']['expiresAt']-int(time.time())
            self.countdown.configure(text=f'有效期剩余 {max(0, left)} 秒')
            if left <= 0:
                self.cancel_invite()
        elif self.update_expires:
            left = self.update_expires - int(time.time())
            self.countdown.configure(text=f'更新原设备连接 · 有效期剩余 {max(0, left)} 秒')
            if left <= 0:
                self.cancel_invite()
        else:
            self.countdown.configure(text='')
        self.root.after(500, self.refresh)

    def close(self):
        if self.closing:
            return
        self.closing = True
        self.root.withdraw()
        completed = threading.Event()
        errors = []
        def finish():
            try:
                self.host.close()
            except Exception as error:
                errors.append(error)
            finally:
                completed.set()
        def poll():
            if not completed.is_set():
                self.root.after(50, poll)
                return
            if errors:
                self.reopen_config = None
                messagebox.showerror('服务关闭未完成', '未切换配置。请保留原数据并重启程序检查连接状态。', parent=self.root)
            self.root.destroy()
        threading.Thread(target=finish, daemon=False).start()
        self.root.after(50, poll)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config')
    parser.add_argument('--self-test', action='store_true', help='synthetic packaging smoke test; starts no network')
    parser.add_argument('--self-test-data', help='new task-owned directory for the offline startup smoke test')
    parser.add_argument('--fresh-start', action='store_true', help='show setup instead of the remembered configuration')
    args = parser.parse_args()
    if args.self_test:
        from pathlib import Path
        from .security import generate_identity, protect, unprotect
        assert unprotect(protect(b'synthetic')) == b'synthetic'
        assert len(generate_identity()[0]) == 64
        assert (Path(__file__).resolve().parents[1]/'desktop'/'runtime_worker.mjs').is_file()
        root = tk.Tk()
        root.withdraw()
        image = qrcode.make('synthetic packaging test')
        ImageTk.PhotoImage(image.get_image(), master=root)
        root.update()
        if args.self_test_data:
            directory = Path(args.self_test_data).resolve()
            wizard = StartupWizard(root, directory)
            wizard.output.set(str(directory/'computer'))
            wizard.create()
            assert wizard.selected == directory/'computer'/'host.json'
            wizard.body.destroy()
            host = Host(wizard.selected)
            try:
                host.start()
                assert not host.threads
                remember_configuration(directory, wizard.selected)
                assert remembered_configuration(directory) == wizard.selected
                window = Window(root, host)
                assert window.invite_button.instate(['disabled'])
                root.update()
                window.closing = True
            finally:
                host.close()
        root.destroy()
        return
    directory = startup_directory()
    selected = args.config or (None if args.fresh_start else remembered_configuration(directory))
    while True:
        root = tk.Tk()
        if not selected:
            wizard = StartupWizard(root, directory)
            root.mainloop()
            selected = wizard.selected
            if not selected:
                root.destroy()
                return
            wizard.body.destroy()
        host = None
        try:
            host = Host(selected)
            host.start()
        except Exception as error:
            if host:
                host.close()
            messagebox.showerror('配置未打开', explain(error), parent=root)
            root.destroy()
            selected = None
            continue
        try:
            remember_configuration(directory, selected)
        except OSError:
            messagebox.showinfo('配置已打开', '无法记住路径；下次可使用“打开已有 host.json”重新导入。', parent=root)
        window = Window(root, host)
        root.mainloop()
        if not host.stop_event.is_set():
            host.close()
        selected = window.reopen_config
        if selected is None:
            return


if __name__ == '__main__':
    main()
