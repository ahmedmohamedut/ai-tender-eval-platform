import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { App, Button, Card, Col, Form, Input, Result, Row, Select, Flex } from 'antd';
import { useStore } from '../store';
import { industries } from '../data';
import { PageHeader } from '../components/common';
import type { Vendor } from '../types';

type Values = Omit<Vendor, 'id' | 'status'> & { status: Vendor['status'] };

export default function VendorForm() {
  const { id } = useParams();
  const s = useStore();
  const nav = useNavigate();
  const loc = useLocation();
  const { message } = App.useApp();
  const [form] = Form.useForm<Values>();
  const existing = id ? s.getVendor(id) : undefined;
  const returnTo = (loc.state as { returnTo?: string } | null)?.returnTo;

  if (!s.isAdmin) return <Result status="403" title="Administrators only" extra={<Button onClick={() => nav('/vendors')}>All vendors</Button>} />;
  if (id && !existing) return <Result status="404" title="Vendor not found" extra={<Button onClick={() => nav('/vendors')}>All vendors</Button>} />;

  const onFinish = (v: Values) => {
    if (existing) { s.updateVendor(existing.id, v); message.success('Vendor updated.'); nav(`/vendors/${existing.id}`); return; }
    const newId = s.addVendor(v);
    message.success(`${v.name} added to the registry.`);
    if (returnTo) nav(returnTo, { state: { pickVendor: newId } }); else nav(`/vendors/${newId}`);
  };
  const dup = (field: 'name' | 'reg') => (_: unknown, val?: string) =>
    val && s.vendors.some((x) => x.id !== id && x[field].trim().toLowerCase() === val.trim().toLowerCase()) ? Promise.reject(new Error(`A vendor with this ${field === 'name' ? 'name' : 'registration number'} already exists.`)) : Promise.resolve();

  return (
    <>
      <PageHeader title={existing ? `Edit ${existing.name}` : 'Create vendor'} subtitle={returnTo ? 'After saving you will return to the tender and the vendor will be preselected.' : 'Vendor information is reused across tenders.'} />
      <Card>
        <Form form={form} layout="vertical" requiredMark="optional" onFinish={onFinish}
          initialValues={existing ?? { status: 'Active', industry: industries[0], country: '' }}>
          <Row gutter={16}>
            <Col xs={24} md={12}><Form.Item name="name" label="Vendor name" rules={[{ required: true, whitespace: true, message: 'Enter the vendor name.' }, { validator: dup('name') }]}><Input /></Form.Item></Col>
            <Col xs={24} md={12}><Form.Item name="legal" label="Legal name" rules={[{ required: true, message: 'Enter the legal name.' }]}><Input /></Form.Item></Col>
            <Col xs={24} md={12}><Form.Item name="reg" label="Registration number" rules={[{ required: true, message: 'Enter the registration number.' }, { validator: dup('reg') }]}><Input /></Form.Item></Col>
            <Col xs={24} md={12}><Form.Item name="country" label="Country" rules={[{ required: true, message: 'Enter the country.' }]}><Input /></Form.Item></Col>
            <Col xs={24} md={12}><Form.Item name="industry" label="Industry"><Select options={industries.map((i) => ({ value: i, label: i }))} /></Form.Item></Col>
            <Col xs={24} md={12}><Form.Item name="website" label="Website"><Input /></Form.Item></Col>
            <Col xs={24} md={8}><Form.Item name="contact" label="Primary contact"><Input /></Form.Item></Col>
            <Col xs={24} md={8}><Form.Item name="email" label="Email" rules={[{ type: 'email', message: 'Enter a valid email.' }]}><Input /></Form.Item></Col>
            <Col xs={24} md={8}><Form.Item name="phone" label="Phone"><Input /></Form.Item></Col>
            <Col xs={24}><Form.Item name="address" label="Address"><Input /></Form.Item></Col>
            <Col xs={24}><Form.Item name="notes" label="Notes"><Input.TextArea rows={3} /></Form.Item></Col>
            <Col xs={24} md={8}><Form.Item name="status" label="Status"><Select options={[{ value: 'Active', label: 'Active' }, { value: 'Inactive', label: 'Inactive' }]} /></Form.Item></Col>
          </Row>
          <Flex justify="end" gap={8}><Button onClick={() => nav(returnTo ?? (existing ? `/vendors/${existing.id}` : '/vendors'))}>Cancel</Button><Button type="primary" htmlType="submit">{existing ? 'Save changes' : 'Create vendor'}</Button></Flex>
        </Form>
      </Card>
    </>
  );
}
